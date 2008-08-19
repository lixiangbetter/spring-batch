/*
 * Copyright 2006-2007 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.batch.core.step.item;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import junit.framework.TestCase;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobInterruptedException;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.UnexpectedJobExecutionException;
import org.springframework.batch.core.job.JobSupport;
import org.springframework.batch.core.listener.StepExecutionListenerSupport;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.dao.MapExecutionContextDao;
import org.springframework.batch.core.repository.dao.MapJobExecutionDao;
import org.springframework.batch.core.repository.dao.MapJobInstanceDao;
import org.springframework.batch.core.repository.dao.MapStepExecutionDao;
import org.springframework.batch.core.repository.support.SimpleJobRepository;
import org.springframework.batch.core.step.AbstractStep;
import org.springframework.batch.core.step.JobRepositorySupport;
import org.springframework.batch.core.step.StepInterruptionPolicy;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemStream;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamSupport;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.MarkFailedException;
import org.springframework.batch.item.ResetFailedException;
import org.springframework.batch.item.support.AbstractItemReader;
import org.springframework.batch.item.support.AbstractItemWriter;
import org.springframework.batch.item.support.ListItemReader;
import org.springframework.batch.repeat.ExitStatus;
import org.springframework.batch.repeat.policy.DefaultResultCompletionPolicy;
import org.springframework.batch.repeat.policy.SimpleCompletionPolicy;
import org.springframework.batch.repeat.support.RepeatTemplate;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.DefaultTransactionStatus;

public class StepHandlerStepTests extends TestCase {

	List<String> processed = new ArrayList<String>();

	private List<Serializable> list = new ArrayList<Serializable>();

	ItemWriter<String> itemWriter = new AbstractItemWriter<String>() {
		public void write(List<? extends String> data) throws Exception {
			processed.addAll(data);
		}
	};

	private StepHandlerStep step;

	private Job job;

	private JobInstance jobInstance;

	private ResourcelessTransactionManager transactionManager;

	private ExecutionContext foobarEc = new ExecutionContext() {
		{
			put("foo", "bar");
		}
	};

	private ItemReader<String> getReader(String[] args) {
		return new ListItemReader<String>(Arrays.asList(args));
	}

	private AbstractStep getStep(String[] strings) throws Exception {
		StepHandlerStep step = new StepHandlerStep("stepName");
		step.setItemHandler(new SimpleStepHandler<String>(getReader(strings), itemWriter));
		step.setJobRepository(new JobRepositorySupport());
		step.setTransactionManager(transactionManager);
		return step;
	}

	protected void setUp() throws Exception {
		MapJobInstanceDao.clear();
		MapStepExecutionDao.clear();
		MapJobExecutionDao.clear();

		transactionManager = new ResourcelessTransactionManager();

		RepeatTemplate template;

		step = (StepHandlerStep) getStep(new String[] { "foo", "bar", "spam" });
		template = new RepeatTemplate();
		template.setCompletionPolicy(new SimpleCompletionPolicy(1));
		step.setStepOperations(template);
		// Only process one item:
		template = new RepeatTemplate();
		template.setCompletionPolicy(new SimpleCompletionPolicy(1));
		step.setChunkOperations(template);

		job = new JobSupport("FOO");
		jobInstance = new JobInstance(new Long(0), new JobParameters(), job.getName());

		step.setTransactionManager(transactionManager);

	}

	public void testStepExecutor() throws Exception {

		JobExecution jobExecutionContext = new JobExecution(jobInstance);
		StepExecution stepExecution = new StepExecution(step.getName(), jobExecutionContext);

		step.execute(stepExecution);
		assertEquals(1, processed.size());
		assertEquals(1, stepExecution.getItemCount());
	}

	/**
	 * StepExecution should be updated after every chunk commit.
	 */
	public void testStepExecutionUpdates() throws Exception {

		JobExecution jobExecution = new JobExecution(jobInstance);
		StepExecution stepExecution = new StepExecution(step.getName(), jobExecution);

		step.setStepOperations(new RepeatTemplate());

		JobRepositoryStub jobRepository = new JobRepositoryStub();
		step.setJobRepository(jobRepository);

		step.execute(stepExecution);

		assertEquals(3, processed.size());
		assertEquals(3, stepExecution.getItemCount());
		assertTrue(3 <= jobRepository.updateCount);
	}

	/**
	 * Failure to update StepExecution after chunk commit is fatal.
	 */
	public void testStepExecutionUpdateFailure() throws Exception {

		JobExecution jobExecution = new JobExecution(jobInstance);
		StepExecution stepExecution = new StepExecution(step.getName(), jobExecution);

		JobRepository repository = new JobRepositoryFailedUpdateStub();

		step.setJobRepository(repository);
		step.afterPropertiesSet();

		try {
			step.execute(stepExecution);
			fail();
		}
		catch (Exception e) {
			assertEquals(BatchStatus.UNKNOWN, stepExecution.getStatus());
			assertEquals("Fatal error detected during update of step execution", e.getMessage());
			assertEquals("stub exception", e.getCause().getMessage());
		}
		
	}

	public void testChunkExecutor() throws Exception {

		RepeatTemplate template = new RepeatTemplate();

		// Only process one item:
		template.setCompletionPolicy(new SimpleCompletionPolicy(1));
		step.setChunkOperations(template);

		JobExecution jobExecution = new JobExecution(jobInstance);

		StepExecution stepExecution = new StepExecution(step.getName(), jobExecution);
		StepContribution contribution = stepExecution.createStepContribution();
		step.processChunk(stepExecution, contribution);
		assertEquals(1, processed.size());
		assertEquals(0, stepExecution.getItemCount());
		assertEquals(1, contribution.getItemCount());

	}

	public void testRepository() throws Exception {

		SimpleJobRepository repository = new SimpleJobRepository(new MapJobInstanceDao(), new MapJobExecutionDao(),
				new MapStepExecutionDao(), new MapExecutionContextDao());
		step.setJobRepository(repository);

		JobExecution jobExecution = repository.createJobExecution(job, jobInstance.getJobParameters());
		StepExecution stepExecution = new StepExecution(step.getName(), jobExecution);

		step.execute(stepExecution);
		assertEquals(1, processed.size());
	}

	public void testIncrementRollbackCount() {

		ItemReader<String> itemReader = new AbstractItemReader<String>() {

			public String read() throws Exception {
				throw new RuntimeException();
			}

		};

		step.setItemHandler(new SimpleStepHandler<String>(itemReader, itemWriter));
		JobExecution jobExecutionContext = new JobExecution(jobInstance);
		StepExecution stepExecution = new StepExecution(step.getName(), jobExecutionContext);

		try {
			step.execute(stepExecution);
		}
		catch (Exception ex) {
			assertEquals(1, stepExecution.getRollbackCount());
		}

	}

	public void testExitCodeDefaultClassification() throws Exception {

		ItemReader<String> itemReader = new AbstractItemReader<String>() {

			public String read() throws Exception {
				throw new RuntimeException();

			}

		};

		step.setItemHandler(new SimpleStepHandler<String>(itemReader, itemWriter));
		JobExecution jobExecutionContext = new JobExecution(jobInstance);
		StepExecution stepExecution = new StepExecution(step.getName(), jobExecutionContext);

		try {
			step.execute(stepExecution);
		}
		catch (Exception ex) {
			ExitStatus status = stepExecution.getExitStatus();
			assertFalse(status.isContinuable());
		}
	}

	public void testExitCodeCustomClassification() throws Exception {

		ItemReader<String> itemReader = new AbstractItemReader<String>() {

			public String read() throws Exception {
				throw new RuntimeException();

			}

		};

		step.setItemHandler(new SimpleStepHandler<String>(itemReader, itemWriter));
		step.registerStepExecutionListener(new StepExecutionListenerSupport() {
			public ExitStatus onErrorInStep(StepExecution stepExecution, Throwable e) {
				return ExitStatus.FAILED.addExitDescription("FOO");
			}
		});
		JobExecution jobExecutionContext = new JobExecution(jobInstance);
		StepExecution stepExecution = new StepExecution(step.getName(), jobExecutionContext);

		try {
			step.execute(stepExecution);
		}
		catch (Exception ex) {
			ExitStatus status = stepExecution.getExitStatus();
			assertFalse(status.isContinuable());
			String description = status.getExitDescription();
			assertTrue("Description does not include 'FOO': " + description, description.indexOf("FOO") >= 0);
		}
	}

	/*
	 * make sure a job that has never been executed before, but does have
	 * saveExecutionAttributes = true, doesn't have restoreFrom called on it.
	 */
	public void testNonRestartedJob() throws Exception {
		MockRestartableItemReader tasklet = new MockRestartableItemReader();
		step.setItemHandler(new SimpleStepHandler<String>(tasklet, itemWriter));
		step.registerStream(tasklet);
		JobExecution jobExecutionContext = new JobExecution(jobInstance);
		StepExecution stepExecution = new StepExecution(step.getName(), jobExecutionContext);

		step.execute(stepExecution);

		assertFalse(tasklet.isRestoreFromCalled());
		assertTrue(tasklet.isGetExecutionAttributesCalled());
	}

	public void testSuccessfulExecutionWithExecutionContext() throws Exception {
		final JobExecution jobExecution = new JobExecution(jobInstance);
		final StepExecution stepExecution = new StepExecution(step.getName(), jobExecution);
		step.setJobRepository(new JobRepositorySupport() {
			public void updateExecutionContext(StepExecution stepExecution) {
				list.add(stepExecution);
			}
		});
		step.execute(stepExecution);

		// context saved before looping and updated once for every processing
		// loop (once in this case) and finally in the abstract step (regardless
		// of execution logic)
		assertEquals(3, list.size());
	}

	public void testSuccessfulExecutionWithFailureOnSaveOfExecutionContext() throws Exception {
		final JobExecution jobExecution = new JobExecution(jobInstance);
		final StepExecution stepExecution = new StepExecution(step.getName(), jobExecution);
		step.setJobRepository(new JobRepositorySupport() {
			private int counter = 0;

			// initial save before item processing succeeds, later calls fail
			public void updateExecutionContext(StepExecution stepExecution) {
				if (counter > 0)
					throw new RuntimeException("foo");
				counter++;
			}
		});
		try {
			step.execute(stepExecution);
			fail();
		}
		catch (RuntimeException e) {
			assertEquals("Fatal error detected during save of step execution context", e.getMessage());
			assertEquals("foo", e.getCause().getMessage());
		}
		assertEquals(BatchStatus.UNKNOWN, stepExecution.getStatus());
	}

	/*
	 * Test that a job that is being restarted, but has saveExecutionAttributes
	 * set to false, doesn't have restore or getExecutionAttributes called on
	 * it.
	 */
	public void testNoSaveExecutionAttributesRestartableJob() {
		MockRestartableItemReader tasklet = new MockRestartableItemReader();
		step.setItemHandler(new SimpleStepHandler<String>(tasklet, itemWriter));
		JobExecution jobExecutionContext = new JobExecution(jobInstance);
		StepExecution stepExecution = new StepExecution(step.getName(), jobExecutionContext);

		try {
			step.execute(stepExecution);
		}
		catch (Throwable t) {
			fail();
		}

		assertFalse(tasklet.isRestoreFromCalled());
	}

	/*
	 * Even though the job is restarted, and saveExecutionAttributes is true,
	 * nothing will be restored because the Tasklet does not implement
	 * Restartable.
	 */
	public void testRestartJobOnNonRestartableTasklet() throws Exception {
		step.setItemHandler(new SimpleStepHandler<String>(new AbstractItemReader<String>() {
			public String read() throws Exception {
				return "foo";
			}
		}, itemWriter));
		JobExecution jobExecution = new JobExecution(jobInstance);
		StepExecution stepExecution = new StepExecution(step.getName(), jobExecution);

		step.execute(stepExecution);
	}

	public void testStreamManager() throws Exception {
		MockRestartableItemReader reader = new MockRestartableItemReader() {
			public String read() throws Exception {
				return "foo";
			}

			public void update(ExecutionContext executionContext) {
				executionContext.putString("foo", "bar");
			}
		};
		step.setItemHandler(new SimpleStepHandler<String>(reader, itemWriter));
		step.registerStream(reader);
		JobExecution jobExecution = new JobExecution(jobInstance);
		StepExecution stepExecution = new StepExecution(step.getName(), jobExecution);

		assertEquals(false, stepExecution.getExecutionContext().containsKey("foo"));

		step.execute(stepExecution);

		// At least once in that process the statistics service was asked for
		// statistics...
		assertEquals("bar", stepExecution.getExecutionContext().getString("foo"));
	}

	public void testDirectlyInjectedItemStream() throws Exception {
		step.setStreams(new ItemStream[] { new ItemStreamSupport() {
			public void update(ExecutionContext executionContext) {
				executionContext.putString("foo", "bar");
			}
		} });
		JobExecution jobExecution = new JobExecution(jobInstance);
		StepExecution stepExecution = new StepExecution(step.getName(), jobExecution);

		assertEquals(false, stepExecution.getExecutionContext().containsKey("foo"));

		step.execute(stepExecution);

		assertEquals("bar", stepExecution.getExecutionContext().getString("foo"));
	}

	public void testDirectlyInjectedListener() throws Exception {
		step.registerStepExecutionListener(new StepExecutionListenerSupport() {
			public void beforeStep(StepExecution stepExecution) {
				list.add("foo");
			}

			public ExitStatus afterStep(StepExecution stepExecution) {
				list.add("bar");
				return null;
			}
		});
		JobExecution jobExecution = new JobExecution(jobInstance);
		StepExecution stepExecution = new StepExecution(step.getName(), jobExecution);
		step.execute(stepExecution);
		assertEquals(2, list.size());
	}

	public void testListenerCalledBeforeStreamOpened() throws Exception {
		MockRestartableItemReader reader = new MockRestartableItemReader() {
			public void beforeStep(StepExecution stepExecution) {
				list.add("foo");
			}

			public void open(ExecutionContext executionContext) throws ItemStreamException {
				assertEquals(1, list.size());
			}
		};
		step.setStreams(new ItemStream[] { reader });
		step.registerStepExecutionListener(reader);
		StepExecution stepExecution = new StepExecution(step.getName(), new JobExecution(jobInstance));
		step.execute(stepExecution);
		assertEquals(1, list.size());
	}

	public void testAfterStep() throws Exception {

		final ExitStatus customStatus = new ExitStatus(false, "custom code");

		step.setStepExecutionListeners(new StepExecutionListener[] { new StepExecutionListenerSupport() {
			public ExitStatus afterStep(StepExecution stepExecution) {
				list.add("afterStepCalled");
				return customStatus;
			}
		} });

		RepeatTemplate stepTemplate = new RepeatTemplate();
		stepTemplate.setCompletionPolicy(new SimpleCompletionPolicy(5));
		step.setStepOperations(stepTemplate);

		JobExecution jobExecution = new JobExecution(jobInstance);
		StepExecution stepExecution = new StepExecution(step.getName(), jobExecution);
		step.execute(stepExecution);
		assertEquals(1, list.size());
		ExitStatus returnedStatus = stepExecution.getExitStatus();
		assertEquals(customStatus.getExitCode(), returnedStatus.getExitCode());
		assertEquals(customStatus.getExitDescription(), returnedStatus.getExitDescription());
	}

	public void testDirectlyInjectedListenerOnError() throws Exception {
		step.registerStepExecutionListener(new StepExecutionListenerSupport() {
			public ExitStatus onErrorInStep(StepExecution stepExecution, Throwable e) {
				list.add(e);
				return null;
			}
		});
		step.setItemHandler(new SimpleStepHandler<String>(new MockRestartableItemReader() {
			public String read() throws Exception {
				throw new RuntimeException("FOO");
			}
		}, itemWriter));
		JobExecution jobExecution = new JobExecution(jobInstance);
		StepExecution stepExecution = new StepExecution(step.getName(), jobExecution);
		try {
			step.execute(stepExecution);
			fail("Expected RuntimeException");
		}
		catch (RuntimeException e) {
			assertEquals("FOO", e.getMessage());
		}
		assertEquals(1, list.size());
	}

	public void testDirectlyInjectedStreamWhichIsAlsoReader() throws Exception {
		MockRestartableItemReader reader = new MockRestartableItemReader() {
			public String read() throws Exception {
				return "foo";
			}

			public void update(ExecutionContext executionContext) {
				executionContext.putString("foo", "bar");
			}
		};
		step.setItemHandler(new SimpleStepHandler<String>(reader, itemWriter));
		step.setStreams(new ItemStream[] { reader });
		JobExecution jobExecution = new JobExecution(jobInstance);
		StepExecution stepExecution = new StepExecution(step.getName(), jobExecution);

		assertEquals(false, stepExecution.getExecutionContext().containsKey("foo"));

		step.execute(stepExecution);

		// At least once in that process the statistics service was asked for
		// statistics...
		assertEquals("bar", stepExecution.getExecutionContext().getString("foo"));
	}

	public void testStatusForInterruptedException() {

		StepInterruptionPolicy interruptionPolicy = new StepInterruptionPolicy() {

			public void checkInterrupted(StepExecution stepExecution) throws JobInterruptedException {
				throw new JobInterruptedException("interrupted");
			}
		};

		step.setInterruptionPolicy(interruptionPolicy);

		ItemReader<String> itemReader = new AbstractItemReader<String>() {

			public String read() throws Exception {
				throw new RuntimeException();

			}

		};

		step.setItemHandler(new SimpleStepHandler<String>(itemReader, itemWriter));

		JobExecution jobExecutionContext = new JobExecution(jobInstance);
		StepExecution stepExecution = new StepExecution(step.getName(), jobExecutionContext);

		stepExecution.setExecutionContext(foobarEc);

		try {
			step.execute(stepExecution);
			fail("Expected JobInterruptedException");
		}
		catch (JobInterruptedException ex) {
			assertEquals(BatchStatus.STOPPED, stepExecution.getStatus());
			String msg = stepExecution.getExitStatus().getExitDescription();
			assertTrue("Message does not contain 'JobInterruptedException': " + msg, contains(msg,
					"JobInterruptedException"));
		}
	}

	public void testStatusForNormalFailure() throws Exception {

		ItemReader<String> itemReader = new AbstractItemReader<String>() {
			public String read() throws Exception {
				// Trigger a rollback
				throw new RuntimeException("Foo");
			}
		};
		step.setItemHandler(new SimpleStepHandler<String>(itemReader, itemWriter));

		JobExecution jobExecutionContext = new JobExecution(jobInstance);
		StepExecution stepExecution = new StepExecution(step.getName(), jobExecutionContext);

		stepExecution.setExecutionContext(foobarEc);
		// step.setLastExecution(stepExecution);

		try {
			step.execute(stepExecution);
			fail("Expected RuntimeException");
		}
		catch (RuntimeException ex) {
			assertEquals(BatchStatus.FAILED, stepExecution.getStatus());
			// The original rollback was caused by this one:
			assertEquals("Foo", ex.getMessage());
		}
	}

	public void testStatusForErrorFailure() throws Exception {

		ItemReader<String> itemReader = new AbstractItemReader<String>() {
			public String read() throws Exception {
				// Trigger a rollback
				throw new Error("Foo");
			}
		};
		step.setItemHandler(new SimpleStepHandler<String>(itemReader, itemWriter));

		JobExecution jobExecutionContext = new JobExecution(jobInstance);
		StepExecution stepExecution = new StepExecution(step.getName(), jobExecutionContext);

		stepExecution.setExecutionContext(foobarEc);
		// step.setLastExecution(stepExecution);

		try {
			step.execute(stepExecution);
			fail("Expected Error");
		}
		catch (Error ex) {
			assertEquals(BatchStatus.FAILED, stepExecution.getStatus());
			// The original rollback was caused by this one:
			assertEquals("Foo", ex.getMessage());
		}
	}

	public void testStatusForResetFailedException() throws Exception {

		ItemReader<String> itemReader = new AbstractItemReader<String>() {
			public String read() throws Exception {
				// Trigger a rollback
				throw new RuntimeException("Foo");
			}
		};
		step.setItemHandler(new SimpleStepHandler<String>(itemReader, itemWriter));
		step.setTransactionManager(new ResourcelessTransactionManager() {
			protected void doRollback(DefaultTransactionStatus status) throws TransactionException {
				// Simulate failure on rollback when stream resets
				throw new ResetFailedException("Bar");
			}
		});

		JobExecution jobExecutionContext = new JobExecution(jobInstance);
		StepExecution stepExecution = new StepExecution(step.getName(), jobExecutionContext);

		stepExecution.setExecutionContext(foobarEc);
		// step.setLastExecution(stepExecution);

		try {
			step.execute(stepExecution);
			fail("Expected UnexpectedJobExecutionException");
		}
		catch (RuntimeException ex) {
			assertEquals(BatchStatus.UNKNOWN, stepExecution.getStatus());
			String msg = stepExecution.getExitStatus().getExitDescription();
			assertTrue("Message does not contain ResetFailedException: " + msg, contains(msg, "ResetFailedException"));
			// The original rollback was caused by this one:
			assertEquals("Bar", ex.getCause().getMessage());
		}
	}

	public void testStatusForCommitFailedException() throws Exception {

		step.setTransactionManager(new ResourcelessTransactionManager() {
			protected void doCommit(DefaultTransactionStatus status) throws TransactionException {
				// Simulate failure on commit
				throw new RuntimeException("Bar");
			}
		});

		JobExecution jobExecutionContext = new JobExecution(jobInstance);
		StepExecution stepExecution = new StepExecution(step.getName(), jobExecutionContext);

		stepExecution.setExecutionContext(foobarEc);
		// step.setLastExecution(stepExecution);

		try {
			step.execute(stepExecution);
			fail("Expected BatchCriticalException");
		}
		catch (RuntimeException ex) {
			assertEquals(BatchStatus.UNKNOWN, stepExecution.getStatus());
			String msg = stepExecution.getExitStatus().getExitDescription();
			assertTrue(msg.contains("Fatal error detected during commit"));
			msg = ex.getMessage();
			assertTrue(msg.contains("Fatal error detected during commit"));
			// The original rollback was caused by this one:
			assertEquals("Bar", ex.getCause().getMessage());
		}
	}

	public void testStatusForFinalUpdateFailedException() throws Exception {

		step.setJobRepository(new JobRepositorySupport());
		step.setStreams(new ItemStream[] { new ItemStreamSupport() {
			public void close(ExecutionContext executionContext) throws ItemStreamException {
				throw new RuntimeException("Bar");
			}
		} });

		JobExecution jobExecutionContext = new JobExecution(jobInstance);
		StepExecution stepExecution = new StepExecution(step.getName(), jobExecutionContext);

		try {
			step.execute(stepExecution);
			fail("Expected RuntimeException");
		}
		catch (RuntimeException ex) {
			// The job actually completed, but the streams couldn't be closed.
			assertEquals(BatchStatus.COMPLETED, stepExecution.getStatus());
			String msg = stepExecution.getExitStatus().getExitDescription();
			assertEquals("", msg);
			msg = ex.getMessage();
			assertTrue("Message does not contain 'closing step': " + msg, contains(msg, "closing step"));
			// The original rollback was caused by this one:
			assertEquals("Bar", ex.getCause().getMessage());
		}
	}

	public void testStatusForCloseFailedException() throws Exception {

		MockRestartableItemReader itemReader = new MockRestartableItemReader() {
			public void close(ExecutionContext executionContext) throws ItemStreamException {
				super.close(executionContext);
				// Simulate failure on rollback when stream resets
				throw new RuntimeException("Bar");
			}
		};
		step.setItemHandler(new SimpleStepHandler<String>(itemReader, itemWriter));
		step.registerStream(itemReader);

		JobExecution jobExecutionContext = new JobExecution(jobInstance);
		StepExecution stepExecution = new StepExecution(step.getName(), jobExecutionContext);

		stepExecution.setExecutionContext(foobarEc);
		// step.setLastExecution(stepExecution);

		try {
			step.execute(stepExecution);
			fail("Expected InfrastructureException");
		}
		catch (UnexpectedJobExecutionException ex) {
			// The job actually completed, but the streams couldn't be closed.
			assertEquals(BatchStatus.COMPLETED, stepExecution.getStatus());
			String msg = stepExecution.getExitStatus().getExitDescription();
			assertEquals("", msg);
			msg = ex.getMessage();
			assertTrue("Message does not contain 'closing': " + msg, contains(msg, "closing"));
			// The original rollback was caused by this one:
			assertEquals("Bar", ex.getCause().getMessage());
		}
	}

	/**
	 * Execution context must not be left empty even if job failed before
	 * commiting first chunk - otherwise ItemStreams won't recognize it is
	 * restart scenario on next run.
	 */
	public void testRestartAfterFailureInFirstChunk() throws Exception {
		MockRestartableItemReader reader = new MockRestartableItemReader() {
			public String read() throws Exception {
				// fail on the very first item
				throw new RuntimeException("CRASH!");
			}
		};
		step.setItemHandler(new SimpleStepHandler<String>(reader, itemWriter));
		step.registerStream(reader);

		StepExecution stepExecution = new StepExecution(step.getName(), new JobExecution(jobInstance));

		try {
			step.execute(stepExecution);
			fail("Expected InfrastructureException");
		}
		catch (RuntimeException expected) {
			assertEquals(BatchStatus.FAILED, stepExecution.getStatus());
			assertEquals("CRASH!", expected.getMessage());
			assertFalse(stepExecution.getExecutionContext().isEmpty());
			assertTrue(stepExecution.getExecutionContext().getString("spam").equals("bucket"));
		}
	}

	public void testStepToCompletion() throws Exception {

		RepeatTemplate template = new RepeatTemplate();

		// process all items:
		template.setCompletionPolicy(new DefaultResultCompletionPolicy());
		step.setStepOperations(template);

		JobExecution jobExecutionContext = new JobExecution(jobInstance);
		StepExecution stepExecution = new StepExecution(step.getName(), jobExecutionContext);

		step.execute(stepExecution);
		assertEquals(3, processed.size());
		assertEquals(3, stepExecution.getItemCount());
	}

	/**
	 * Exception in {@link StepExecutionListener#afterStep(StepExecution)}
	 * causes step to fail.
	 * @throws JobInterruptedException
	 */
	public void testStepFailureInAfterStepCallback() throws JobInterruptedException {
		StepExecutionListener listener = new StepExecutionListenerSupport() {
			public ExitStatus afterStep(StepExecution stepExecution) {
				throw new RuntimeException("exception thrown in afterStep to signal failure");
			}
		};
		step.setStepExecutionListeners(new StepExecutionListener[] { listener });
		StepExecution stepExecution = new StepExecution(step.getName(), new JobExecution(jobInstance));
		try {
			step.execute(stepExecution);
			fail();
		}
		catch (RuntimeException expected) {
			assertEquals("exception thrown in afterStep to signal failure", expected.getMessage());
		}

		assertEquals(BatchStatus.FAILED, stepExecution.getStatus());

	}

	private boolean contains(String str, String searchStr) {
		return str.indexOf(searchStr) != -1;
	}

	private static class JobRepositoryStub extends JobRepositorySupport {

		private int updateCount = 0;

		@Override
		public void update(StepExecution stepExecution) {
			updateCount++;
			if (updateCount <= 3) {
				assertEquals(updateCount, stepExecution.getItemCount());
			}
		}

	}
	
	private static class JobRepositoryFailedUpdateStub extends JobRepositorySupport {
		
		private boolean firstCall = true;
		
		public void update(StepExecution stepExecution) {
			if (firstCall) {
				firstCall = false;
				throw new DataAccessResourceFailureException("stub exception");
			}
		}
	}

	private class MockRestartableItemReader extends ItemStreamSupport implements ItemReader<String>,
			StepExecutionListener {

		private boolean getExecutionAttributesCalled = false;

		private boolean restoreFromCalled = false;

		private boolean restoreFromCalledWithSomeContext = false;

		public String read() throws Exception {
			return "item";
		}

		public boolean isRestoreFromCalledWithSomeContext() {
			return restoreFromCalledWithSomeContext;
		}

		public void update(ExecutionContext executionContext) {
			getExecutionAttributesCalled = true;
			executionContext.putString("spam", "bucket");
		}

		public boolean isGetExecutionAttributesCalled() {
			return getExecutionAttributesCalled;
		}

		public boolean isRestoreFromCalled() {
			return restoreFromCalled;
		}

		public void mark() throws MarkFailedException {
		}

		public void reset() throws ResetFailedException {
		}

		public ExitStatus afterStep(StepExecution stepExecution) {
			return null;
		}

		public void beforeStep(StepExecution stepExecution) {
		}

		public ExitStatus onErrorInStep(StepExecution stepExecution, Throwable e) {
			return null;
		}

	}

}
