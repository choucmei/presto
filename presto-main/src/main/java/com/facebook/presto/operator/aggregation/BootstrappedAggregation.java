/*
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
package com.facebook.presto.operator.aggregation;

import com.facebook.presto.block.Block;
import com.facebook.presto.block.BlockBuilder;
import com.facebook.presto.block.BlockCursor;
import com.facebook.presto.operator.GroupByIdBlock;
import com.facebook.presto.operator.Page;
import com.facebook.presto.serde.PagesSerde;
import com.facebook.presto.tuple.TupleInfo;
import com.facebook.presto.tuple.TupleInfo.Type;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import io.airlift.slice.BasicSliceInput;
import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.SliceInput;
import io.airlift.slice.SliceOutput;
import org.apache.commons.math.random.RandomData;
import org.apache.commons.math.random.RandomDataImpl;
import org.apache.commons.math.stat.descriptive.DescriptiveStatistics;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static com.facebook.presto.tuple.TupleInfo.SINGLE_DOUBLE;
import static com.facebook.presto.tuple.TupleInfo.SINGLE_LONG;
import static com.facebook.presto.tuple.TupleInfo.SINGLE_VARBINARY;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

public class BootstrappedAggregation
        implements AggregationFunction
{
    private static final int SAMPLES = 100;
    private final AggregationFunction function;

    public BootstrappedAggregation(AggregationFunction function)
    {
        this.function = checkNotNull(function, "function is null");
        checkArgument(function.getFinalTupleInfo().equals(SINGLE_LONG) || function.getFinalTupleInfo().equals(SINGLE_DOUBLE) , "bootstrap only supports functions that output a number");
    }

    @Override
    public List<Type> getParameterTypes()
    {
        return function.getParameterTypes();
    }

    @Override
    public TupleInfo getFinalTupleInfo()
    {
        return SINGLE_VARBINARY;
    }

    @Override
    public TupleInfo getIntermediateTupleInfo()
    {
        return SINGLE_VARBINARY;
    }

    @Override
    public Accumulator createAggregation(Optional<Integer> maskChannel, Optional<Integer> sampleWeightChannel, double confidence, int... argumentChannels)
    {
        checkArgument(sampleWeightChannel.isPresent(), "sample weight must be present for bootstrapping");
        return createDeterministicAggregation(maskChannel, sampleWeightChannel.get(), confidence, ThreadLocalRandom.current().nextLong(), argumentChannels);
    }

    @Override
    public Accumulator createIntermediateAggregation(double confidence)
    {
        return createDeterministicIntermediateAggregation(confidence, ThreadLocalRandom.current().nextLong());
    }

    @Override
    public GroupedAccumulator createGroupedAggregation(Optional<Integer> maskChannel, Optional<Integer> sampleWeightChannel, double confidence, int... argumentChannels)
    {
        checkArgument(sampleWeightChannel.isPresent(), "sample weight must be present for bootstrapping");
        return createDeterministicGroupedAggregation(maskChannel, sampleWeightChannel.get(), confidence, ThreadLocalRandom.current().nextLong(), argumentChannels);
    }

    @Override
    public GroupedAccumulator createGroupedIntermediateAggregation(double confidence)
    {
        return createDeterministicGroupedIntermediateAggregation(confidence, ThreadLocalRandom.current().nextLong());
    }

    @VisibleForTesting
    public Accumulator createDeterministicAggregation(Optional<Integer> maskChannel, int sampleWeightChannel, double confidence, long seed, int... argumentChannels)
    {
        ImmutableList.Builder<Accumulator> builder = ImmutableList.builder();
        for (int i = 0; i < SAMPLES; i++) {
            builder.add(function.createAggregation(maskChannel, Optional.of(sampleWeightChannel), 1.0, argumentChannels));
        }
        return new BootstrappedAccumulator(builder.build(), sampleWeightChannel, confidence, seed);
    }

    @VisibleForTesting
    public Accumulator createDeterministicIntermediateAggregation(double confidence, long seed)
    {
        ImmutableList.Builder<Accumulator> builder = ImmutableList.builder();
        for (int i = 0; i < SAMPLES; i++) {
            builder.add(function.createIntermediateAggregation(1.0));
        }
        return new BootstrappedAccumulator(builder.build(), -1, confidence, seed);
    }

    @VisibleForTesting
    public GroupedAccumulator createDeterministicGroupedAggregation(Optional<Integer> maskChannel, int sampleWeightChannel, double confidence, long seed, int... argumentChannels)
    {
        ImmutableList.Builder<GroupedAccumulator> builder = ImmutableList.builder();
        for (int i = 0; i < SAMPLES; i++) {
            builder.add(function.createGroupedAggregation(maskChannel, Optional.of(sampleWeightChannel), 1.0, argumentChannels));
        }
        return new BootstrappedGroupedAccumulator(builder.build(), sampleWeightChannel, confidence, seed);
    }

    @VisibleForTesting
    public GroupedAccumulator createDeterministicGroupedIntermediateAggregation(double confidence, long seed)
    {
        ImmutableList.Builder<GroupedAccumulator> builder = ImmutableList.builder();
        for (int i = 0; i < SAMPLES; i++) {
            builder.add(function.createGroupedIntermediateAggregation(1.0));
        }
        return new BootstrappedGroupedAccumulator(builder.build(), -1, confidence, seed);
    }

    public abstract static class AbstractBootstrappedAccumulator
    {
        protected final int sampleWeightChannel;
        protected final double confidence;
        private final RandomData rand;

        public AbstractBootstrappedAccumulator(int sampleWeightChannel, double confidence, long seed)
        {
            this.sampleWeightChannel = sampleWeightChannel;
            this.confidence = confidence;
            RandomDataImpl rand = new RandomDataImpl();
            rand.reSeed(seed);
            this.rand = rand;
        }

        protected Block resampleWeightBlock(Block block)
        {
            return new PoissonizedBlock(block, rand.nextLong(0, Long.MAX_VALUE - 1));
        }

        protected static double getNumeric(BlockCursor cursor)
        {
            if (cursor.getTupleInfo().equals(SINGLE_DOUBLE)) {
                return cursor.getDouble();
            }
            else if (cursor.getTupleInfo().equals(SINGLE_LONG)) {
                return cursor.getLong();
            }
            else {
                throw new AssertionError("expected a numeric cursor");
            }
        }
    }

    public static class BootstrappedAccumulator
            extends AbstractBootstrappedAccumulator
            implements Accumulator
    {
        private final List<Accumulator> accumulators;

        public BootstrappedAccumulator(List<Accumulator> accumulators, int sampleWeightChannel, double confidence, long seed)
        {
            super(sampleWeightChannel, confidence, seed);

            this.accumulators = ImmutableList.copyOf(checkNotNull(accumulators, "accumulators is null"));
            checkArgument(accumulators.size() > 1, "accumulators size is less than 2");
        }

        @Override
        public TupleInfo getFinalTupleInfo()
        {
            return SINGLE_VARBINARY;
        }

        @Override
        public TupleInfo getIntermediateTupleInfo()
        {
            return SINGLE_VARBINARY;
        }

        @Override
        public void addInput(Page page)
        {
            Block[] blocks = Arrays.copyOf(page.getBlocks(), page.getChannelCount());
            for (int i = 0; i < accumulators.size(); i++) {
                blocks[sampleWeightChannel] = resampleWeightBlock(page.getBlock(sampleWeightChannel));
                accumulators.get(i).addInput(new Page(blocks));
            }
        }

        @Override
        public void addIntermediate(Block block)
        {
            BlockCursor cursor = block.cursor();
            checkArgument(cursor.advanceNextPosition());
            SliceInput sliceInput = new BasicSliceInput(cursor.getSlice());
            Page page = Iterators.getOnlyElement(PagesSerde.readPages(sliceInput));
            checkArgument(page.getChannelCount() == accumulators.size(), "number of blocks does not match accumulators");

            for (int i = 0; i < page.getChannelCount(); i++) {
                accumulators.get(i).addIntermediate(page.getBlock(i));
            }
        }

        @Override
        public Block evaluateIntermediate()
        {
            Block[] blocks = new Block[accumulators.size()];
            int sizeEstimate = 64 * accumulators.size();
            for (int i = 0; i < accumulators.size(); i++) {
                blocks[i] = accumulators.get(i).evaluateIntermediate();
                sizeEstimate += blocks[i].getDataSize().toBytes();
            }

            SliceOutput output = new DynamicSliceOutput(sizeEstimate);
            PagesSerde.writePages(output, new Page(blocks));
            BlockBuilder builder = new BlockBuilder(SINGLE_VARBINARY);
            builder.append(output.slice());
            return builder.build();
        }

        @Override
        public Block evaluateFinal()
        {
            DescriptiveStatistics statistics = new DescriptiveStatistics();
            for (int i = 0; i < accumulators.size(); i++) {
                BlockCursor cursor = accumulators.get(i).evaluateFinal().cursor();
                checkArgument(cursor.advanceNextPosition(), "accumulator returned no results");
                statistics.addValue(getNumeric(cursor));
            }

            BlockBuilder builder = new BlockBuilder(SINGLE_VARBINARY);
            builder.append(formatApproximateOutput(statistics, confidence));
            return builder.build();
        }
    }

    public static class BootstrappedGroupedAccumulator
            extends AbstractBootstrappedAccumulator
            implements GroupedAccumulator
    {
        private final List<GroupedAccumulator> accumulators;

        public BootstrappedGroupedAccumulator(List<GroupedAccumulator> accumulators, int sampleWeightChannel, double confidence, long seed)
        {
            super(sampleWeightChannel, confidence, seed);

            this.accumulators = ImmutableList.copyOf(checkNotNull(accumulators, "accumulators is null"));
            checkArgument(accumulators.size() > 1, "accumulators size is less than 2");
        }

        @Override
        public long getEstimatedSize()
        {
            long size = 0;
            for (GroupedAccumulator accumulator : accumulators) {
                size += accumulator.getEstimatedSize();
            }
            return size;
        }

        @Override
        public TupleInfo getFinalTupleInfo()
        {
            return SINGLE_VARBINARY;
        }

        @Override
        public TupleInfo getIntermediateTupleInfo()
        {
            return SINGLE_VARBINARY;
        }

        @Override
        public void addInput(GroupByIdBlock groupIdsBlock, Page page)
        {
            Block[] blocks = Arrays.copyOf(page.getBlocks(), page.getChannelCount());
            for (int i = 0; i < accumulators.size(); i++) {
                blocks[sampleWeightChannel] = resampleWeightBlock(page.getBlock(sampleWeightChannel));
                accumulators.get(i).addInput(groupIdsBlock, new Page(blocks));
            }
        }

        @Override
        public void addIntermediate(GroupByIdBlock groupIdsBlock, Block block)
        {
            BlockCursor cursor = block.cursor();
            checkArgument(cursor.advanceNextPosition());
            SliceInput sliceInput = new BasicSliceInput(cursor.getSlice());
            Page page = Iterators.getOnlyElement(PagesSerde.readPages(sliceInput));
            checkArgument(page.getChannelCount() == accumulators.size(), "number of blocks does not match accumulators");

            for (int i = 0; i < page.getChannelCount(); i++) {
                accumulators.get(i).addIntermediate(groupIdsBlock, page.getBlock(i));
            }
        }

        @Override
        public void evaluateIntermediate(int groupId, BlockBuilder output)
        {
            Block[] blocks = new Block[accumulators.size()];
            int sizeEstimate = 64 * accumulators.size();
            for (int i = 0; i < accumulators.size(); i++) {
                BlockBuilder builder = new BlockBuilder(accumulators.get(i).getIntermediateTupleInfo());
                accumulators.get(i).evaluateIntermediate(groupId, builder);
                blocks[i] = builder.build();
                sizeEstimate += blocks[i].getDataSize().toBytes();
            }

            SliceOutput sliceOutput = new DynamicSliceOutput(sizeEstimate);
            PagesSerde.writePages(sliceOutput, new Page(blocks));
            output.append(sliceOutput.slice());
        }

        @Override
        public void evaluateFinal(int groupId, BlockBuilder output)
        {
            DescriptiveStatistics statistics = new DescriptiveStatistics();
            for (int i = 0; i < accumulators.size(); i++) {
                BlockBuilder builder = new BlockBuilder(accumulators.get(i).getFinalTupleInfo());
                accumulators.get(i).evaluateFinal(groupId, builder);
                BlockCursor cursor = builder.build().cursor();
                checkArgument(cursor.advanceNextPosition(), "accumulator returned no results");
                statistics.addValue(getNumeric(cursor));
            }

            output.append(formatApproximateOutput(statistics, confidence));
        }
    }

    private static String formatApproximateOutput(DescriptiveStatistics statistics, double confidence)
    {
        StringBuilder sb = new StringBuilder();
        double p = 100 * (1 + confidence) / 2.0;
        double upper = statistics.getPercentile(p);
        double lower = statistics.getPercentile(100 - p);
        sb.append((upper + lower) / 2.0);
        sb.append(" +/- ");
        double error = (upper - lower) / 2.0;
        checkState(error >= 0, "error is negative");
        sb.append(error);

        return sb.toString();
    }
}
