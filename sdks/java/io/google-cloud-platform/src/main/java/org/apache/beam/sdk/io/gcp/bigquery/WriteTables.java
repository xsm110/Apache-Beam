/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.beam.sdk.io.gcp.bigquery;

import com.google.api.services.bigquery.model.Job;
import com.google.api.services.bigquery.model.JobConfigurationLoad;
import com.google.api.services.bigquery.model.JobReference;
import com.google.api.services.bigquery.model.TableReference;
import com.google.api.services.bigquery.model.TableSchema;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nullable;

import org.apache.beam.sdk.io.gcp.bigquery.BigQueryHelpers.Status;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.Write;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.Write.CreateDisposition;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.Write.WriteDisposition;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryServices.DatasetService;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryServices.JobService;
import org.apache.beam.sdk.options.BigQueryOptions;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.apache.beam.sdk.util.FileIOChannelFactory;
import org.apache.beam.sdk.util.GcsIOChannelFactory;
import org.apache.beam.sdk.util.GcsUtil;
import org.apache.beam.sdk.util.GcsUtil.GcsUtilFactory;
import org.apache.beam.sdk.util.IOChannelFactory;
import org.apache.beam.sdk.util.IOChannelUtils;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollectionView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Writes partitions to BigQuery tables.
 */
class WriteTables extends DoFn<KV<Long, Iterable<List<String>>>, String> {
  private static final Logger LOG = LoggerFactory.getLogger(WriteTables.class);

  private final boolean singlePartition;
  private final BigQueryServices bqServices;
  private final PCollectionView<String> jobIdToken;
  private final String tempFilePrefix;
  private final ValueProvider<String> jsonTableRef;
  private final ValueProvider<String> jsonSchema;
  private final WriteDisposition writeDisposition;
  private final CreateDisposition createDisposition;
  @Nullable
  private final String tableDescription;

  public WriteTables(
      boolean singlePartition,
      BigQueryServices bqServices,
      PCollectionView<String> jobIdToken,
      String tempFilePrefix,
      ValueProvider<String> jsonTableRef,
      ValueProvider<String> jsonSchema,
      WriteDisposition writeDisposition,
      CreateDisposition createDisposition,
      @Nullable String tableDescription) {
    this.singlePartition = singlePartition;
    this.bqServices = bqServices;
    this.jobIdToken = jobIdToken;
    this.tempFilePrefix = tempFilePrefix;
    this.jsonTableRef = jsonTableRef;
    this.jsonSchema = jsonSchema;
    this.writeDisposition = writeDisposition;
    this.createDisposition = createDisposition;
    this.tableDescription = tableDescription;
  }

  @ProcessElement
  public void processElement(ProcessContext c) throws Exception {
    List<String> partition = Lists.newArrayList(c.element().getValue()).get(0);
    String jobIdPrefix = String.format(
        c.sideInput(jobIdToken) + "_%05d", c.element().getKey());
    TableReference ref = BigQueryHelpers.fromJsonString(jsonTableRef.get(),
        TableReference.class);
    if (!singlePartition) {
      ref.setTableId(jobIdPrefix);
    }

    load(
        bqServices.getJobService(c.getPipelineOptions().as(BigQueryOptions.class)),
        bqServices.getDatasetService(c.getPipelineOptions().as(BigQueryOptions.class)),
        jobIdPrefix,
        ref,
        BigQueryHelpers.fromJsonString(
            jsonSchema == null ? null : jsonSchema.get(), TableSchema.class),
        partition,
        writeDisposition,
        createDisposition,
        tableDescription);
    c.output(BigQueryHelpers.toJsonString(ref));

    removeTemporaryFiles(c.getPipelineOptions(), tempFilePrefix, partition);
  }

  private void load(
      JobService jobService,
      DatasetService datasetService,
      String jobIdPrefix,
      TableReference ref,
      @Nullable TableSchema schema,
      List<String> gcsUris,
      WriteDisposition writeDisposition,
      CreateDisposition createDisposition,
      @Nullable String tableDescription) throws InterruptedException, IOException {
    JobConfigurationLoad loadConfig = new JobConfigurationLoad()
        .setDestinationTable(ref)
        .setSchema(schema)
        .setSourceUris(gcsUris)
        .setWriteDisposition(writeDisposition.name())
        .setCreateDisposition(createDisposition.name())
        .setSourceFormat("NEWLINE_DELIMITED_JSON");

    String projectId = ref.getProjectId();
    Job lastFailedLoadJob = null;
    for (int i = 0; i < Write.MAX_RETRY_JOBS; ++i) {
      String jobId = jobIdPrefix + "-" + i;
      JobReference jobRef = new JobReference()
          .setProjectId(projectId)
          .setJobId(jobId);
      jobService.startLoadJob(jobRef, loadConfig);
      Job loadJob = jobService.pollJob(jobRef, Write.LOAD_JOB_POLL_MAX_RETRIES);
      Status jobStatus = BigQueryHelpers.parseStatus(loadJob);
      switch (jobStatus) {
        case SUCCEEDED:
          if (tableDescription != null) {
            datasetService.patchTableDescription(ref, tableDescription);
          }
          return;
        case UNKNOWN:
          throw new RuntimeException(String.format(
              "UNKNOWN status of load job [%s]: %s.", jobId,
              BigQueryHelpers.jobToPrettyString(loadJob)));
        case FAILED:
          lastFailedLoadJob = loadJob;
          continue;
        default:
          throw new IllegalStateException(String.format(
              "Unexpected status [%s] of load job: %s.",
              jobStatus, BigQueryHelpers.jobToPrettyString(loadJob)));
      }
    }
    throw new RuntimeException(String.format(
        "Failed to create load job with id prefix %s, "
            + "reached max retries: %d, last failed load job: %s.",
        jobIdPrefix,
        Write.MAX_RETRY_JOBS,
        BigQueryHelpers.jobToPrettyString(lastFailedLoadJob)));
  }

  static void removeTemporaryFiles(
      PipelineOptions options,
      String tempFilePrefix,
      Collection<String> files)
      throws IOException {
    IOChannelFactory factory = IOChannelUtils.getFactory(tempFilePrefix);
    if (factory instanceof GcsIOChannelFactory) {
      GcsUtil gcsUtil = new GcsUtilFactory().create(options);
      gcsUtil.remove(files);
    } else if (factory instanceof FileIOChannelFactory) {
      for (String filename : files) {
        LOG.debug("Removing file {}", filename);
        boolean exists = Files.deleteIfExists(Paths.get(filename));
        if (!exists) {
          LOG.debug("{} does not exist.", filename);
        }
      }
    } else {
      throw new IOException("Unrecognized file system.");
    }
  }

  @Override
  public void populateDisplayData(DisplayData.Builder builder) {
    super.populateDisplayData(builder);

    builder
        .addIfNotNull(DisplayData.item("tempFilePrefix", tempFilePrefix)
            .withLabel("Temporary File Prefix"))
        .addIfNotNull(DisplayData.item("jsonTableRef", jsonTableRef)
            .withLabel("Table Reference"))
        .addIfNotNull(DisplayData.item("jsonSchema", jsonSchema)
            .withLabel("Table Schema"))
        .addIfNotNull(DisplayData.item("tableDescription", tableDescription)
            .withLabel("Table Description"));
  }
}
