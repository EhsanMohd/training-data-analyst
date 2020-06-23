/*
 * Copyright (C) 2018 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mypackage.pipeline;

import com.google.common.annotations.VisibleForTesting;
import com.google.gson.Gson;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.schemas.transforms.AddFields;
import org.apache.beam.sdk.schemas.transforms.Convert;
import org.apache.beam.sdk.schemas.transforms.Group;
import org.apache.beam.sdk.transforms.*;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.windowing.IntervalWindow;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.Row;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link BatchMinuteTrafficPipeline} is a sample pipeline which can be used as a base for creating a real
 * Dataflow pipeline.
 *
 * <p><b>Pipeline Requirements</b>
 *
 * <ul>
 *   <li>Requirement #1
 *   <li>Requirement #2
 * </ul>
 *
 * <p><b>Example Usage</b>
 *
 * <pre>
 * # Set the pipeline vars
 * PROJECT_ID=PROJECT_ID
 * PIPELINE_FOLDER=gs://${PROJECT_ID}/dataflow/pipelines/sample-pipeline
 *
 * # Set the runner
 * RUNNER=DataflowRunner
 *
 * # Build the template
 * mvn compile exec:java \
 * -Dexec.mainClass=com.mypackage.pipeline.BatchUserTrafficPipeline \
 * -Dexec.cleanupDaemonThreads=false \
 * -Dexec.args=" \
 * --project=${PROJECT_ID} \
 * --stagingLocation=${PIPELINE_FOLDER}/staging \
 * --tempLocation=${PIPELINE_FOLDER}/temp \
 * --runner=${RUNNER} \
 * ADDITIONAL PARAMETERS HERE"
 * </pre>
 */
public class StreamingMinuteTrafficPipeline {

    /*
     * The logger to output status messages to.
     */
    private static final Logger LOG = LoggerFactory.getLogger(StreamingMinuteTrafficPipeline.class);

    /**
     * The {@link Options} class provides the custom execution options passed by the executor at the
     * command-line.
     */
    public interface Options extends PipelineOptions {
        @Description("Window duration length, in minutes")
        Integer getWindowDuration();
        void setWindowDuration(Integer windowDuration);

        @Description("BigQuery table name")
        String getTableName();
        void setTableName(String tableName);

        @Description("Inpute topic name")
        String getInputTopic();
        void setInputTopic(String inputTopic);

        @Description("BigQuery raw table name")
        String getRawTableName();
        void setRawTableName(String rawTableName);

    }

    @VisibleForTesting
    /**
     * A DoFn acccepting Json and outputing CommonLog with Beam Schema
     */
    static class JsonToCommonLog extends DoFn<String, CommonLog> {

        @ProcessElement
        public void processElement(@Element String json, OutputReceiver<CommonLog> r) throws Exception {
            Gson gson = new Gson();
            CommonLog commonLog = gson.fromJson(json, CommonLog.class);
            r.output(commonLog);
        }
    }

    public static final Schema pageviewsSchema = Schema
            .builder()
            .addInt64Field("pageviews")
            .addDateTimeField("second")
            .build();

    /**
     * The main entry-point for pipeline execution. This method will start the pipeline but will not
     * wait for it's execution to finish. If blocking execution is required, use the {@link
     * BatchMinuteTrafficPipeline#run(Options)} method to start the pipeline and invoke
     * {@code result.waitUntilFinish()} on the {@link PipelineResult}.
     *
     * @param args The command-line args passed by the executor.
     */
    public static void main(String[] args) {
        PipelineOptionsFactory.register(Options.class);
        Options options = PipelineOptionsFactory.fromArgs(args)
                .withValidation()
                .as(Options.class);
        run(options);
    }

    /**
     * Runs the pipeline to completion with the specified options. This method does not wait until the
     * pipeline is finished before returning. Invoke {@code result.waitUntilFinish()} on the result
     * object to block until the pipeline is finished running if blocking programmatic execution is
     * required.
     *
     * @param options The execution options.
     * @return The pipeline result.
     */
    public static PipelineResult run(Options options) {

        // Create the pipeline
        Pipeline pipeline = Pipeline.create(options);
        options.setJobName("batch-user-traffic-pipeline-" + System.currentTimeMillis());

        /*
         * Steps:
         *  1) Read something
         *  2) Transform something
         *  3) Write something
         */

        LOG.info("Building pipeline...");

        PCollection<Row> rows = pipeline
                .apply("ReadMessage", PubsubIO.readStrings()
                                .withTimestampAttribute("timestamp")
                                .fromTopic(options.getInputTopic()))

                .apply("ParseJson", ParDo.of(new JsonToCommonLog()))
                .apply(Convert.toRows());

        // Window and write to BQ
        rows
                .apply("WindowByMinute", Window.into(FixedWindows.of(Duration.standardSeconds(options.getWindowDuration()))))
                .apply("CountPerMinute", Combine.globally(Count.<Row>combineFn()).withoutDefaults())
                .apply("ConvertToRow", ParDo.of(new DoFn<Long, Row>() {
                    @ProcessElement
                    public void processElement(@Element Long views, OutputReceiver<Row> r, IntervalWindow window) {
                        Instant i = Instant.ofEpochMilli(window.end().getMillis());
                        Row row = Row.withSchema(pageviewsSchema)
                                .addValues(views, i)
                                .build();
                        r.output(row);
                    }
                })).setRowSchema(pageviewsSchema)
        // TODO: is this a streaming insert?
        .apply("WriteToBQ",
                BigQueryIO.<Row>write().to(options.getTableName()).useBeamSchema()
                        // TODO: mention you need WRITE_APPEND
                        .withWriteDisposition(BigQueryIO.Write.WriteDisposition.WRITE_APPEND)
                        .withCreateDisposition(BigQueryIO.Write.CreateDisposition.CREATE_IF_NEEDED));

        // Write raw to BQ
        // But we want to add a processing time indicator as well
        rows
                .apply("AddProcessingTimeField", AddFields.<Row>create().field("processing_timestamp", Schema.FieldType.DATETIME))
                .apply("AddProcessingTime", MapElements.via(new SimpleFunction<Row, Row>() {
                    @Override
                    public Row apply(Row row) {
                        // TODO: Fix with Row builder
                        return Row.withSchema(row.getSchema())
                                .addValues(
                                        row.getString("user_id"),
                                        row.getString("ip"),
                                        row.getDouble("lat"),
                                        row.getDouble("lng"),
                                        row.getString("timestamp"),
                                        row.getString("http_request"),
                                        row.getString("user_agent"),
                                        row.getInt32("http_response"),
                                        row.getInt32("num_bytes"),
                                        DateTime.now())
                                .build();
                    }
                }
                ))


                .apply("WriteRawToBQ",
                        BigQueryIO.<Row>write().to(options.getRawTableName()).useBeamSchema()
                                // TODO: mention you need WRITE_APPEND
                                .withWriteDisposition(BigQueryIO.Write.WriteDisposition.WRITE_APPEND)
                                .withCreateDisposition(BigQueryIO.Write.CreateDisposition.CREATE_IF_NEEDED));


        // Next Lab?
        // Use SQL Query
        // Then Do through UI
        // Show Topic Preview

        return pipeline.run();
    }
}
