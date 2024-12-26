/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.util.asyncprocessing;

import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.ClosureCleaner;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.api.operators.Input;
import org.apache.flink.streaming.api.operators.MultipleInputStreamOperator;
import org.apache.flink.streaming.api.operators.StreamOperatorFactory;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.io.RecordProcessorUtils;
import org.apache.flink.streaming.runtime.streamrecord.RecordAttributes;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.watermarkstatus.WatermarkStatus;
import org.apache.flink.streaming.util.MultiInputStreamOperatorTestHarness;
import org.apache.flink.util.function.ThrowingConsumer;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.apache.flink.streaming.util.asyncprocessing.AsyncProcessingTestUtil.drain;
import static org.apache.flink.streaming.util.asyncprocessing.AsyncProcessingTestUtil.execute;

/**
 * A test harness for testing a {@link MultipleInputStreamOperator}.
 *
 * <p>All methods that interact with the operator need to be executed in another thread to simulate
 * async processing, please use methods of test harness instead of operator.
 */
public class AsyncKeyedMultiInputStreamOperatorTestHarness<K, OUT>
        extends MultiInputStreamOperatorTestHarness<OUT> {

    /** The executor service for async state processing. */
    private final ExecutorService executor;

    public static <K, OUT> AsyncKeyedMultiInputStreamOperatorTestHarness<K, OUT> create(
            StreamOperatorFactory<OUT> operatorFactory,
            TypeInformation<K> keyType,
            List<KeySelector<?, K>> keySelectors,
            int maxParallelism,
            int numSubtasks,
            int subtaskIndex)
            throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CompletableFuture<AsyncKeyedMultiInputStreamOperatorTestHarness<K, OUT>> future =
                new CompletableFuture<>();
        executor.execute(
                () -> {
                    try {
                        future.complete(
                                new AsyncKeyedMultiInputStreamOperatorTestHarness<>(
                                        executor,
                                        operatorFactory,
                                        keyType,
                                        keySelectors,
                                        maxParallelism,
                                        numSubtasks,
                                        subtaskIndex));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
        return future.get();
    }

    private AsyncKeyedMultiInputStreamOperatorTestHarness(
            ExecutorService executor,
            StreamOperatorFactory<OUT> operatorFactory,
            TypeInformation<K> keyType,
            List<KeySelector<?, K>> keySelectors,
            int maxParallelism,
            int numSubtasks,
            int subtaskIndex)
            throws Exception {
        super(operatorFactory, maxParallelism, numSubtasks, subtaskIndex);
        config.setStateKeySerializer(
                keyType.createSerializer(executionConfig.getSerializerConfig()));
        config.serializeAllConfigs();
        for (int i = 0; i < keySelectors.size(); i++) {
            setKeySelector(i, keySelectors.get(i));
        }
        this.executor = executor;
    }

    public void setKeySelector(int idx, KeySelector<?, K> keySelector) {
        ClosureCleaner.clean(keySelector, ExecutionConfig.ClosureCleanerLevel.RECURSIVE, false);
        config.setStatePartitioner(idx, keySelector);
        config.serializeAllConfigs();
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void processElement(int idx, StreamRecord<?> element) throws Exception {
        Input input = getCastedOperator().getInputs().get(idx);
        ThrowingConsumer<StreamRecord<?>, Exception> inputProcessor =
                RecordProcessorUtils.getRecordProcessor(input);
        execute(executor, (ignore) -> inputProcessor.accept(element)).get();
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void processWatermark(int idx, Watermark mark) throws Exception {
        Input input = getCastedOperator().getInputs().get(idx);
        execute(executor, (ignore) -> input.processWatermark(mark)).get();
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void processWatermarkStatus(int idx, WatermarkStatus watermarkStatus) throws Exception {
        Input input = getCastedOperator().getInputs().get(idx);
        execute(executor, (ignore) -> input.processWatermarkStatus(watermarkStatus)).get();
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void processRecordAttributes(int idx, RecordAttributes recordAttributes)
            throws Exception {
        Input input = getCastedOperator().getInputs().get(idx);
        execute(executor, (ignore) -> input.processRecordAttributes(recordAttributes)).get();
    }

    public void drainStateRequests() throws Exception {
        execute(executor, (ignore) -> drain(operator)).get();
    }

    @Override
    public void close() throws Exception {
        execute(executor, (ignore) -> super.close()).get();
        executor.shutdown();
    }
}
