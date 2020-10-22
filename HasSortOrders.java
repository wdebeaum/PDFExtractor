package TRIPS.PDFExtractor;

import java.util.Comparator;
import TRIPS.KQML.KQMLList;
import TRIPS.KQML.KQMLObject;

public interface HasSortOrders<E> {
  /** Convert the :order-by argument to a Comparator for this class. */
  Comparator<E> orderFromKQML(KQMLList orderByKQML);
  /** Return the string to use for the :expected argument of the
   * invalid-argument failure report for when orderFromKQML throws an
   * IllegalArgumentException.
   */
  String kqmlExpectedForOrder();

  KQMLObject toKQML(boolean notInList); // for compatibility with Region
}
