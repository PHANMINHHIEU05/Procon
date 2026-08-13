package vn.ptit.procon.domain.traffic;

import java.math.BigInteger;

/** Exact non-negative normalized traffic flow represented as a reduced fraction. */
public record TrafficFlow(long numerator, long denominator) implements Comparable<TrafficFlow> {

    public TrafficFlow {
        if (numerator < 0) {
            throw new IllegalArgumentException("Traffic flow numerator must be non-negative: " + numerator);
        }
        if (denominator <= 0) {
            throw new IllegalArgumentException("Traffic flow denominator must be positive: " + denominator);
        }

        long divisor = greatestCommonDivisor(numerator, denominator);
        numerator /= divisor;
        denominator /= divisor;
    }

    public static TrafficFlow of(long wholeValue) {
        return new TrafficFlow(wholeValue, 1);
    }

    @Override
    public int compareTo(TrafficFlow other) {
        if (other == null) {
            throw new NullPointerException("Compared traffic flow must not be null");
        }
        BigInteger left = BigInteger.valueOf(numerator).multiply(BigInteger.valueOf(other.denominator));
        BigInteger right = BigInteger.valueOf(other.numerator).multiply(BigInteger.valueOf(denominator));
        return left.compareTo(right);
    }

    private static long greatestCommonDivisor(long left, long right) {
        while (right != 0) {
            long remainder = left % right;
            left = right;
            right = remainder;
        }
        return left;
    }
}