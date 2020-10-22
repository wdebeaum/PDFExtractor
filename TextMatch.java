package TRIPS.PDFExtractor;

import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.*;
import technology.tabula.RectangularTextContainer;
import TRIPS.KQML.KQMLList;
import TRIPS.KQML.KQMLObject;
import TRIPS.KQML.KQMLPerformative;
import TRIPS.KQML.KQMLString;
import TRIPS.KQML.KQMLToken;

/** A result of a text search, with context. The result will be from either a
 * paragraph Region, or a Table cell. Only one of region or table will be set;
 * the other will be null.
 */
public class TextMatch implements HasSortOrders<TextMatch> {
  /** The substring that matched. */
  public final String found;
  /** The full text of the region or table cell that found was taken from. */
  public final String context;
  /** The [start,end) interval of indexes into context where found was taken
   * from.
   */
  public final int start, end;
  /** The paragraph Region the match was found in, if any. */
  public final Region region;
  /** The table the match was found in, if any. */
  public final Table table;
  /** The (row,column) coordinates of the cell the match was found in.
   * Will be (0,0) for region matches.
   */
  public final int row, col;

  public TextMatch(String found, String context, int start, int end, Region region, Table table, int row, int col) {
    this.found   = found;
    this.context = context;
    this.start   = start;
    this.end     = end;
    this.region  = region;
    this.table   = table;
    this.row     = row;
    this.col     = col;
  }

  /** Return a new TextMatch with context from the given Region. */
  public static TextMatch inRegion(Region region, int start, int end) {
    String context = region.getText();
    String found = context.substring(start, end);
    return new TextMatch(found, context, start, end, region, null, 0, 0);
  }

  /** Return a new version of this TextMatch with the given Region as its
   * context.
   */
  public TextMatch inRegion(Region region) {
    return new TextMatch(found, context, start, end, region, null, 0, 0);
  }

  /** Return a new TextMatch with context from the given table cell. */
  public static TextMatch inCell(Table table, int row, int col, int start, int end) {
    RectangularTextContainer cell = table.getCellAt(row, col);
    String context = Cell.getTextOf(cell);
    String found = context.substring(start, end);
    return new TextMatch(found, context, start, end, null, table, row, col);
  }

  /** Return a new version of this TextMatch with the given table cell as its
   * context.
   */
  public TextMatch inCell(Table table, int row, int col) {
    return new TextMatch(found, context, start, end, null, table, row, col);
  }

  /** Return a new TextMatch with no context beyond the context string itself.
   * Call inRegion() or inCell() to give it context.
   */
  public static TextMatch nowhere(String context, int start, int end) {
    String found = context.substring(start, end);
    return new TextMatch(found, context, start, end, null, null, 0, 0);
  }

  /** Convert a search string (as used by Searchable.search(String)) to a regex
   * Pattern.
   */
  public static Pattern searchStringToPattern(String searchStr) {
    String patternStr =
      searchStr.
      replaceAll("([^\\w\\s])", "\\\\$1"). // escape non-word, non-space chars
      replaceAll("^\\b|\\b$", "\\\\b"). // add word boundary matchers
      replaceAll("\\s+", "\\\\s+"); // replace whitespace with matchers
    return Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
  }

  /** Search the given context string for the given regex pattern, and return a
   * TextMatch (with no other context) for each matching substring.
   */
  public static List<TextMatch> search(Pattern searchPattern, String context) {
    List<TextMatch> matches = new LinkedList<TextMatch>();
    Matcher m = searchPattern.matcher(context);
    while (m.find()) {
      matches.add(nowhere(context, m.start(), m.end()));
    }
    return matches;
  }

  public KQMLObject toKQML() {
    KQMLPerformative p = new KQMLPerformative("match");
    p.setParameter(":found", new KQMLString(found));
    p.setParameter(":start", ""+start);
    p.setParameter(":end", ""+end);
    p.setParameter(":context", new KQMLString(context));
    if (region != null)
      p.setParameter(":region", region.toKQML());
    if (table != null)
      p.setParameter(":cell",
        (new TableSelection(table, row, col, row, col)).toKQML());
    return p;
  }

  /** Has text that is capable of being searched. */
  public interface Searchable extends HasID {
    /** Search the text of this object for the given regex Pattern, and return
     * a TextMatch for each matching substring. Classes implementing Searchable
     * should implement this function by calling TextMatch.search on their
     * text, and/or recursing on their Searchable parts.
     */
    List<TextMatch> search(Pattern searchPattern);
    /** Search the text of this object for the given string, and return a
     * TextMatch for each matching substring. This search is case-insensitive
     * and non-word-splitting, and any string of whitespace will match any
     * other string of whitespace.
     */
    default List<TextMatch> search(String searchString) {
      return search(searchStringToPattern(searchString));
    }
  }

  /** An ordering to sort TextMatches by. */
  public static class Order implements Comparator<TextMatch> {
    /** Where to get the value to sort by. */
    enum Key {
      REGION_ORDER, PAGE_INDEX, ROW, COLUMN;

      /** Get the value to sort by. */
      public double of(TextMatch m, Region.Order regionOrder) {
	switch (this) {
	  case REGION_ORDER: return regionOrder.key.of(m.region);
	  case PAGE_INDEX: return (double)m.region.getPage().getPageIndex();
	  case ROW: return (double)m.row;
	  case COLUMN: return (double)m.col;
	  default: throw new RuntimeException("WTF java, this is exhaustive.");
	}
      }

      public KQMLObject toKQML() {
	return new KQMLToken(name().toLowerCase().replace('_', '-'));
      }

      public static Key fromKQML(KQMLToken k) {
	String s = k.toString().toUpperCase().replace('-', '_');
	try {
	  return Enum.valueOf(Key.class, s);
	} catch (IllegalArgumentException ex) {
	  return REGION_ORDER;
	}
      }
    };
    public final Region.Order regionOrder;
    public final Key key;
    /** Direction to sort in. */
    public final boolean ascending;

    /** Construct a TextMatch.Order that just defers to a Region.Order, for
     * TextMatches with Region context.
     */
    public Order(Region.Order regionOrder) {
      this.regionOrder = regionOrder;
      this.key = Key.REGION_ORDER;
      this.ascending = true;
    }

    /** Construct a TextMatch.Order that does its own ordering. */
    public Order(Key key, boolean ascending) {
      this.regionOrder = null;
      this.key = key;
      this.ascending = ascending;
    }

    public KQMLObject toKQML() {
      if (regionOrder == null) {
	return new KQMLList(key.toKQML(),
			    new KQMLToken(ascending ? "asc" : "desc"));
      } else {
	return regionOrder.toKQML();
      }
    }

    public static Order fromKQML(KQMLList l) {
      if (!(l.size() == 2 &&
	    (l.get(0) instanceof KQMLToken) &&
	    (l.get(1) instanceof KQMLToken)))
	throw new IllegalArgumentException();
      Key key = Key.fromKQML((KQMLToken)l.get(0));
      if (key == Key.REGION_ORDER) {
	return new Order(Region.Order.fromKQML(l));
      } else {
	boolean ascending = Region.Order.ascendingFromKQML(l.get(1));
	return new Order(key, ascending);
      }
    }

    @Override
    public int compare(TextMatch a, TextMatch b) {
      return Double.compare(key.of(a, regionOrder), key.of(b, regionOrder)) *
             (ascending ? 1 : -1);
    }
  }
  @Override
  public Comparator<TextMatch> orderFromKQML(KQMLList l) {
    return Order.fromKQML(l);
  }
  @Override
  public String kqmlExpectedForOrder() {
    return "list of two tokens: ({{max|min}-{x|y}|width|height|page-index|row|column} {asc|desc})";
  }
  @Override
  public KQMLObject toKQML(boolean notInList) { return toKQML(); }
}
