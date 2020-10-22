package TRIPS.PDFExtractor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import TRIPS.KQML.*;
import TRIPS.util.cwc.*;

/** A set of results that can be sorted and selected from using SQL-like
 * arguments.
 */
public class ResultSet<E extends HasSortOrders<E>> {
  /** The elements of the set. */
  List<E> data;
  /** Hard limit on the number of elements to select. May be null. */
  Integer limit;
  /** Soft limit on the number of elements to select. If this limit is reached,
   * any later elements that compare equal to the last element that would
   * otherwise be included are also included. May be null.
   */
  Integer softLimit;
  Comparator<E> orderBy;

  public ResultSet(List<E> data, KQMLPerformative requestContent) throws CWCException {
    this.data = (data == null ? new ArrayList<E>() : data);
    if (requestContent != null) setSelectArguments(requestContent);
  }

  // java y u no have default arguments?

  public ResultSet() throws CWCException {
    this(null, null);
  }

  public ResultSet(List<E> data) throws CWCException {
    this(data, null);
  }

  /** Set the SQL-like arguments from the given request content. Must be called
   * after data is added.
   */
  public void setSelectArguments(KQMLPerformative content) throws CWCException {
    limit = Args.getTypedArgument(content, ":limit", Integer.class, null);
    softLimit =
      Args.getTypedArgument(content, ":soft-limit", Integer.class, null);
    KQMLList orderByKQML =
      Args.getTypedArgument(content, ":order-by", KQMLList.class, null);
    if (orderByKQML != null && !data.isEmpty()) {
      // we need an instance of E, not just E itself, in order to access
      // E.Order (via HasSortOrders<E>) because E itself gets erased from
      // ResultSet<E>
      E anE = data.get(0);
      try {
	orderBy = anE.orderFromKQML(orderByKQML);
      } catch (IllegalArgumentException ex) {
	throw new InvalidArgument(content, ":order-by", anE.kqmlExpectedForOrder());
      }
    }
    // sanity checks
    if (softLimit != null) {
      if (softLimit < 1)
	throw new InvalidArgument(content, ":soft-limit", "at least 1");
      if (orderByKQML == null)
	throw new InvalidArgumentCombo(":soft-limit requires :order-by");
      if (limit != null && softLimit.compareTo(limit) > 0)
	throw new InvalidArgumentCombo(":soft-limit should be less than :limit if both are used");
    }
  }

  /** Set limit and softLimit from the given request content, and set orderBy
   * separately.
   */
  public void setSelectArguments(KQMLPerformative content, Comparator<E> orderBy) throws CWCException {
    limit = Args.getTypedArgument(content, ":limit", Integer.class, null);
    softLimit =
      Args.getTypedArgument(content, ":soft-limit", Integer.class, null);
    this.orderBy = orderBy;
    // sanity checks
    if (softLimit != null) {
      if (softLimit < 1)
	throw new InvalidArgument(content, ":soft-limit", "at least 1");
      if (limit != null && softLimit.compareTo(limit) > 0)
	throw new InvalidArgumentCombo(":soft-limit should be less than :limit if both are used");
    }
  }

  public void add(E result) { data.add(result); }

  /** Sort the data and/or keep only the first few, according to the arguments.
   */
  public void select() {
    if (orderBy != null) data.sort(orderBy);
    if (limit != null) data = data.subList(0, Math.min(data.size(), limit));
    if (softLimit != null && softLimit < data.size()) {
      E last = data.get(softLimit - 1);
      for (int len = softLimit; len < data.size(); len++) {
	if (orderBy.compare(last, data.get(len)) != 0) {
	  data = data.subList(0, len);
	  break;
	}
      }
    }
  }

  public KQMLList toKQML() {
    KQMLList dataKQML = new KQMLList();
    for (E e : data) {
      dataKQML.add(e.toKQML(false));
    }
    return dataKQML;
  }
}
