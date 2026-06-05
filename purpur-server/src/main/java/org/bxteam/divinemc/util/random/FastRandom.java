package org.bxteam.divinemc.util.random;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.BitRandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomSupport;

/**
 * Fast, single-threaded {@link RandomSource} backed by xoroshiro128++.
 *
 * <p>Intended for runtime, non-worldgen hot paths (mob AI, particles, item drop physics, ...) where
 * {@link net.minecraft.world.level.levelgen.LegacyRandomSource}'s LCG + rejection loops show up in
 * profiles. It is a drop-in replacement for {@link net.minecraft.world.level.levelgen.SingleThreadedRandomSource}:
 * same {@link BitRandomSource} contract, same single-thread usage assumption.
 *
 * <p>NOT for worldgen: it does not reproduce vanilla's positional sequences. {@link #forkPositional()}
 * returns a usable but non-vanilla factory, fine for the runtime uses above.
 */
public final class FastRandom implements BitRandomSource {
    private long s0;
    private long s1;

    private double nextNextGaussian;
    private boolean haveNextNextGaussian;

    public FastRandom() {
        this(RandomSupport.generateUniqueSeed());
    }

    public FastRandom(final long seed) {
        this.setSeed(seed);
    }

    private static long stafford13(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    @Override
    public void setSeed(final long seed) {
        // SplitMix64 expansion of the 64-bit seed into the 128-bit state; guarantee non-zero state.
        final long z = seed ^ 0x9E3779B97F4A7C15L;
        this.s0 = stafford13(z);
        this.s1 = stafford13(z + 0x9E3779B97F4A7C15L);
        if ((this.s0 | this.s1) == 0L) this.s1 = 0x9E3779B97F4A7C15L;
        this.haveNextNextGaussian = false;
    }

    private long nextBits64() {
        final long s0 = this.s0;
        long s1 = this.s1;
        final long result = Long.rotateLeft(s0 + s1, 17) + s0; // ++ scrambler
        s1 ^= s0;
        this.s0 = Long.rotateLeft(s0, 49) ^ s1 ^ (s1 << 21);
        this.s1 = Long.rotateLeft(s1, 28);
        return result;
    }

    @Override
    public int next(final int bits) {
        return (int) (this.nextBits64() >>> (64 - bits));
    }

    @Override
    public long nextLong() {
        // Override the two-next(32) default: one 64-bit draw is faster and higher quality here.
        return this.nextBits64();
    }

    @Override
    public double nextGaussian() {
        if (this.haveNextNextGaussian) {
            this.haveNextNextGaussian = false;
            return this.nextNextGaussian;
        }
        double v1, v2, s;
        do {
            v1 = 2.0 * this.nextDouble() - 1.0;
            v2 = 2.0 * this.nextDouble() - 1.0;
            s = v1 * v1 + v2 * v2;
        } while (s >= 1.0 || s == 0.0);
        final double multiplier = Math.sqrt(-2.0 * Math.log(s) / s);
        this.nextNextGaussian = v2 * multiplier;
        this.haveNextNextGaussian = true;
        return v1 * multiplier;
    }

    @Override
    public RandomSource fork() {
        return new FastRandom(this.nextBits64());
    }

    @Override
    public PositionalRandomFactory forkPositional() {
        return new Positional(this.nextBits64());
    }

    /** Non-vanilla positional factory; deterministic for a given (base seed, position/name). */
    public static final class Positional implements PositionalRandomFactory {
        private final long seed;

        private Positional(final long seed) {
            this.seed = seed;
        }

        @Override
        public RandomSource fromHashOf(final String name) {
            return new FastRandom(this.seed ^ stafford13(name.hashCode()));
        }

        @Override
        public RandomSource fromSeed(final long seed) {
            return new FastRandom(this.seed ^ stafford13(seed));
        }

        @Override
        public RandomSource at(final int x, final int y, final int z) {
            long h = this.seed;
            h ^= stafford13(x * 0x9E3779B97F4A7C15L);
            h ^= stafford13(y * 0xC2B2AE3D27D4EB4FL);
            h ^= stafford13(z * 0x165667B19E3779F9L);
            return new FastRandom(h);
        }

        @Override
        public RandomSource at(final BlockPos pos) {
            return this.at(pos.getX(), pos.getY(), pos.getZ());
        }

        @Override
        public RandomSource fromHashOf(final Identifier name) {
            return this.fromHashOf(name.toString());
        }

        @Override
        public void parityConfigString(final StringBuilder sb) {
            sb.append("FastRandom.Positional{seed=").append(this.seed).append('}');
        }
    }
}
