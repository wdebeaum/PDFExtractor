package TRIPS.PDFExtractor;

import java.awt.geom.RectangularShape;
import TRIPS.KQML.*;

/** Relationships between one-dimensional intervals.
 * https://en.wikipedia.org/wiki/Allen%27s_interval_algebra
 */
public enum IntervalRelation {
  // NOTE: this ordering is special, don't change it.
  BEFORE,
  MEETS,
  OVERLAPS,
  FINISHED_BY,
  AROUND,
  STARTS,
  EQUAL,
  STARTED_BY,
  INSIDE,
  FINISHES,
  OVERLAPPED_BY,
  MET_BY,
  AFTER;

  /** Convert this relation to a KQML token. e.g. MET_BY ↦ met-by */
  public KQMLObject toKQML() {
    return new KQMLToken(name().toLowerCase().replace('_', '-'));
  }

  /** Inverse of toKQML(). */
  public static IntervalRelation fromKQML(KQMLToken k) {
    return Enum.valueOf(IntervalRelation.class,
			k.toString().toUpperCase().replace('-', '_'));
  }

  // cache this so we don't keep allocating new copies
  private final static IntervalRelation[] vals = IntervalRelation.values();

  /** Get the inverse relation to this one.
   * That is, if this == IntervalRelation.of(as,ae,bs,be),
   * then this.inverse() == IntervalRelation.of(bs,be,as,ae).
   * e.g. BEFORE ↦ AFTER, EQUAL ↦ EQUAL
   */
  public IntervalRelation getInverse() {
    return vals[12 - ordinal()];
  }

  /** Return an integer in the range [1,5] that describes where point p lies in
   * relation to interval i (iStart-iEnd).
   * Assumes iStart.compareTo(iEnd) &lt; 0.
   *
   *      iStart     iEnd
   *         |---------|
   *    1    2    3    4    5
   */
  static <T extends Comparable<T>> int pointIntervalRelation(
      T p, T iStart, T iEnd) {
    int s = p.compareTo(iStart);
    if (s < 0) {
      return 1;
    } else if (s == 0) {
      return 2;
    } else { // s > 0
      int e = p.compareTo(iEnd);
      if (e < 0) {
	return 3;
      } else if (e == 0) {
	return 4;
      } else { // e > 0
        return 5;
      }
    }
  }

  /** Get the relationship between two intervals, [aStart,aEnd] and
   * [bStart,bEnd]. These must be well-formed, non-empty intervals, i.e. start
   * strictly less than end.
   */
  public static <T extends Comparable<T>> IntervalRelation of(
      T aStart, T aEnd, T bStart, T bEnd) {
    // first compute the relationship of each of the endpoints of a to the
    // interval b
    // aStart relative to b
    int asrb = pointIntervalRelation(aStart, bStart, bEnd);
    // aEnd relative to b
    int aerb = pointIntervalRelation(aEnd, bStart, bEnd);
    // then make them into the digits of a 2-digit number, and switch on it
    switch (asrb*10 + aerb) {
      case 11: return BEFORE;
      case 12: return MEETS;
      case 13: return OVERLAPS;
      case 14: return FINISHED_BY;
      case 15: return AROUND;
      case 23: return STARTS;
      case 24: return EQUAL;
      case 25: return STARTED_BY;
      case 33: return INSIDE;
      case 34: return FINISHES;
      case 35: return OVERLAPPED_BY;
      case 45: return MET_BY;
      case 55: return AFTER;
      default: throw new IllegalArgumentException("degenerate interval " + asrb + aerb + "; a=[" + aStart + "," + aEnd + "]; b=[" + bStart + "," + bEnd + "]");
    }
  }

  /* unused * Get a pair of Relations, one for each dimension, for a pair of
   * rectangular shapes.
   * /
  public static IntervalRelation[] of(RectangularShape a, RectangularShape b) {
    IntervalRelation[] ret = new IntervalRelation[2];
    ret[0] =
      IntervalRelation.of(a.getMinX(), a.getMaxX(), b.getMinX(), b.getMaxX());
    ret[1] =
      IntervalRelation.of(a.getMinY(), a.getMaxY(), b.getMinY(), b.getMaxY());
    return ret;
  }*/
}

