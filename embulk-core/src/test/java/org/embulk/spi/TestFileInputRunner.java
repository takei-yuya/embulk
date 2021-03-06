package org.embulk.spi;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.embulk.EmbulkTestRuntime;
import org.embulk.config.CommitReport;
import org.embulk.config.ConfigSource;
import org.embulk.config.ConfigDiff;
import org.embulk.config.TaskSource;
import org.embulk.spi.time.Timestamp;
import org.embulk.spi.TestPageBuilderReader.MockPageOutput;
import org.embulk.spi.Schema;
import org.embulk.spi.util.Pages;

public class TestFileInputRunner
{
    @Rule
    public EmbulkTestRuntime runtime = new EmbulkTestRuntime();

    @Before
    public void tearDown()
    {
        MockParserPlugin.raiseException = false;
    }

    private static class MockFileInputPlugin implements FileInputPlugin
    {
        Boolean transactionCompleted = null;
        Queue<Buffer> buffers;

        public MockFileInputPlugin(Queue<Buffer> buffers)
        {
            this.buffers = buffers;
        }

        @Override
        public ConfigDiff transaction(ConfigSource config,
                FileInputPlugin.Control control)
        {
            control.run(Exec.newTaskSource(), 1);
            return null;
        }

        @Override
        public ConfigDiff resume(TaskSource taskSource,
                int taskCount,
                FileInputPlugin.Control control)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cleanup(TaskSource taskSource,
                int taskCount,
                List<CommitReport> successCommitReports)
        {
        }

        public TransactionalFileInput open(TaskSource taskSource,
                int taskIndex)
        {
            return new TransactionalFileInput()
            {
                @Override
                public Buffer poll()
                {
                    return buffers.poll();
                }

                @Override
                public boolean nextFile()
                {
                    return !buffers.isEmpty();
                }

                @Override
                public void close()
                {
                }

                @Override
                public void abort()
                {
                    transactionCompleted = false;
                }

                @Override
                public CommitReport commit()
                {
                    transactionCompleted = true;
                    return null;
                }
            };
        }
    }

    @Test
    public void testMockParserIteration()
    {
        Buffer[] buffers = new Buffer[] {
                runtime.getBufferAllocator().allocate(),
                runtime.getBufferAllocator().allocate() };
        MockFileInputPlugin fileInputPlugin = new MockFileInputPlugin(
                new LinkedList<Buffer>(Arrays.asList(buffers)));
        final FileInputRunner runner = new FileInputRunner(fileInputPlugin);

        ConfigSource config = Exec.newConfigSource().set(
                "parser",
                ImmutableMap.of("type", "mock", "columns", ImmutableList.of(
                        ImmutableMap.of("name", "col1", "type", "boolean"),
                        ImmutableMap.of("name", "col2", "type", "long"),
                        ImmutableMap.of("name", "col3", "type", "double"),
                        ImmutableMap.of("name", "col4", "type", "string"),
                        ImmutableMap.of("name", "col5", "type", "timestamp"))));

        final MockPageOutput output = new MockPageOutput();
        runner.transaction(config, new InputPlugin.Control()
        {
            public List<CommitReport> run(TaskSource inputTaskSource,
                    Schema schema, int taskCount)
            {
                List<CommitReport> reports = new ArrayList<>();
                reports.add(runner.run(inputTaskSource, schema, 0, output));
                return reports;
            }
        });

        assertEquals(true, fileInputPlugin.transactionCompleted);
        assertEquals(1, output.pages.size());

        Schema schema = config.getNested("parser")
                .loadConfig(MockParserPlugin.PluginTask.class)
                .getSchemaConfig().toSchema();

        List<Object[]> records = Pages.toObjects(schema, output.pages);
        assertEquals(2, records.size());
        for (Object[] record : records) {
            assertEquals(5, record.length);
            assertEquals(true, record[0]);
            assertEquals(2L, record[1]);
            assertEquals(3.0D, (Double) record[2], 0.01D);
            assertEquals("45", record[3]);
            assertEquals(678L, ((Timestamp) record[4]).toEpochMilli());
        }
    }

    @Test
    public void testTransactionAborted()
    {
        Buffer[] buffers = new Buffer[] {
                runtime.getBufferAllocator().allocate(),
                runtime.getBufferAllocator().allocate() };
        MockFileInputPlugin fileInputPlugin = new MockFileInputPlugin(
                new LinkedList<Buffer>(Arrays.asList(buffers)));
        final FileInputRunner runner = new FileInputRunner(fileInputPlugin);

        ConfigSource config = Exec.newConfigSource().set(
                "parser",
                ImmutableMap.of("type", "mock", "columns", ImmutableList.of(
                        ImmutableMap.of("name", "col1", "type", "boolean"),
                        ImmutableMap.of("name", "col2", "type", "long"),
                        ImmutableMap.of("name", "col3", "type", "double"),
                        ImmutableMap.of("name", "col4", "type", "string"),
                        ImmutableMap.of("name", "col5", "type", "timestamp"))));

        final MockPageOutput output = new MockPageOutput();

        MockParserPlugin.raiseException = true;

        try {
            runner.transaction(config, new InputPlugin.Control()
            {
                public List<CommitReport> run(TaskSource inputTaskSource,
                        Schema schema, int taskCount)
                {
                    List<CommitReport> reports = new ArrayList<>();
                    reports.add(runner.run(inputTaskSource, schema, 0, output));
                    return reports;
                }
            });
        } catch (RuntimeException re) {
        }
        assertEquals(false, fileInputPlugin.transactionCompleted);
        assertEquals(0, output.pages.size());
    }
}
