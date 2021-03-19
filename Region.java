package TRIPS.PDFExtractor;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Map;
import java.util.HashMap;
import java.util.regex.Pattern;
import java.awt.Color;
import java.awt.Graphics;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
//import technology.tabula.Page; conflicts
import technology.tabula.HasText;
import technology.tabula.Rectangle;
import technology.tabula.RectangularTextContainer;
import technology.tabula.TextChunk;
import technology.tabula.TextElement;
import TRIPS.KQML.KQMLBadPerformativeException;
import TRIPS.KQML.KQMLObject;
import TRIPS.KQML.KQMLList;
import TRIPS.KQML.KQMLPerformative;
import TRIPS.KQML.KQMLString;
import TRIPS.KQML.KQMLToken;
import TRIPS.util.cwc.Args;
import TRIPS.util.cwc.CWCException;
import TRIPS.util.cwc.InvalidArgument;
import TRIPS.util.cwc.UnknownObject;

/** Represents a rectangular region of a {@link Page} in a PDF {@link Document}.
 */
public class Region implements HasID, HasHTML, TextMatch.Searchable, HasSortOrders<Region> {
  final String id;
  @Override public String getID() { return id; }
  Rectangle rect;
  Page page;
  Color color;
  boolean highlighted;
  boolean fresh; // can't use "new"
  public static enum Source {
    /** The user manually selected the region. */
    USER,
    /** The system selected the region over KQML. */
    SYSTEM,
    /** We detected the region as a table. */
    TABLE,
    /** We detected the region as a paragraph. */
    PARAGRAPH,
    /** We detected the region as a horizontal ruling. */
    HORIZONTAL,
    /** We detected the region as a vertical ruling. */
    VERTICAL;
  };
  final Source source;

  public static enum NamedColor {
    // from XKCD color naming survey:
    // https://blog.xkcd.com/2010/05/03/color-survey-results/
    // these names are also ONT types under ONT::color-val.
    // black, white, and metallic colors are excluded.
    PURPLE (0x7E1E9C),
    GREEN  (0x15B01A),
    BLUE   (0x0343DF),
    PINK   (0xFF81C0),
    BROWN  (0x653700),
    RED    (0xE50000),
    ORANGE (0xF97306),
    MAGENTA(0xC20078),
    YELLOW (0xFFFF14),
    GRAY   (0x929591),
    TAN    (0xD1B26F);
    public final Color color;
    private NamedColor(int rgb) {
      this.color = new Color(rgb);
    }
  };

  public static enum Coord { X1, Y1, X2, Y2 };

  //accessors
  public double getX1() { return rect.getX(); }
  public double getY1() { return rect.getY(); }
  public double getX2() { return rect.getX() + rect.getWidth() - 1.0; }
  public double getY2() { return rect.getY() + rect.getHeight() - 1.0; }
  // NOTE: rect.get{Min,Max}{X,Y}() don't account for negative dimensions
  public double getMinX() { return Math.min(getX1(), getX2()); }
  public double getMinY() { return Math.min(getY1(), getY2()); }
  public double getMaxX() { return Math.max(getX1(), getX2()); }
  public double getMaxY() { return Math.max(getY1(), getY2()); }
  public double getCenterX() { return rect.getCenterX(); }
  public double getCenterY() { return rect.getCenterY(); }
  public double getAbsWidth() { return Math.abs(rect.getWidth()); }
  public double getAbsHeight() { return Math.abs(rect.getHeight()); }
  public double getSignedWidth() { return rect.getWidth(); }
  public double getSignedHeight() { return rect.getHeight(); }
  public Page getPage() { return page; }
  public Color getColor() { return color; }
  public void setColor(Color c) { color = c; }

  @Override
  public String getText() {
    if (rect instanceof RectangularTextContainer) {
      return ((RectangularTextContainer)rect).getText();
    } else {
      // TODO maybe just do this, since it would still apply to RTCs?
      return getHTML(new HTMLBuilder()).toTextString();
    }
  }

  @Override
  public HTMLBuilder getHTML(HTMLBuilder out) {
    // get TextElements within rect
    List<TextElement> textElements = toTabulaPage().getText();
    // group textElements into lines using TextChunker
    TextChunker tc = new TextChunker();
    tc.coordIsX = false;
    tc.addAll(textElements);
    List<TextChunk> lines = tc.chunk();
    // sort by increasing center Y coordinate
    Collections.sort(lines, new Comparator<TextChunk>() {
      @Override public int compare(TextChunk a, TextChunk b) {
	return Double.compare(a.getCenterY(), b.getCenterY());
      }
    });
    // output each line followed by <br>
    for (TextChunk line : lines)
      Cell.getHTMLOf(line, out).br();
    return out;
  }

  /** Rearrange coordinates to get rid of any negative width or height. */
  public void normalize() {
    rect.setRect(getMinX(), getMinY(), getAbsWidth(), getAbsHeight());
  }
  
  /** Set X2 and Y2. */
  public void setXY2(double x, double y) {
    // clamp x,y to be within page
    PDRectangle pageBB = page.getPDBBox();
    x = Math.max(pageBB.getLowerLeftX(), Math.min(pageBB.getUpperRightX(), x));
    y = Math.max(pageBB.getLowerLeftY(), Math.min(pageBB.getUpperRightY(), y));
    // set width,height based on x,y and existing x1,y1
    double x1 = rect.getX(), y1 = rect.getY();
    rect.setRect(x1, y1, x - x1 + 1.0, y - y1 + 1.0);
    page.emit(new Page.Event(Page.Event.Type.REGION_CHANGED, this));
  }

  /** Set the coordinates of one of the four corners, specified by coords,
   * which should contain one X and one Y.
   */
  public void setCoords(EnumSet<Coord> coords, double x, double y) {
    double x1 = coords.contains(Coord.X1) ? x : getX1(),
	   y1 = coords.contains(Coord.Y1) ? y : getY1(),
	   x2 = coords.contains(Coord.X2) ? x : getX2(),
	   y2 = coords.contains(Coord.Y2) ? y : getY2();
    rect.setRect(clampRectToPage(x1, y1, x2, y2, page));
    page.emit(new Page.Event(Page.Event.Type.REGION_CHANGED, this));
  }

  final double HANDLE_RADIUS = 5; // pixels

  /** Get the coords to pass to setCoords if we start dragging from (x,y). */
  public EnumSet<Coord> getHandleAt(double x, double y) {
    EnumSet<Coord> ret = EnumSet.noneOf(Coord.class);
    if (rect.intersects(x - HANDLE_RADIUS, y - HANDLE_RADIUS,
			HANDLE_RADIUS * 2 + 1, HANDLE_RADIUS * 2 + 1)) {
      if (Math.abs(x - getX1()) <= HANDLE_RADIUS) ret.add(Coord.X1);
      if (Math.abs(y - getY1()) <= HANDLE_RADIUS) ret.add(Coord.Y1);
      if (Math.abs(x - getX2()) <= HANDLE_RADIUS) ret.add(Coord.X2);
      if (Math.abs(y - getY2()) <= HANDLE_RADIUS) ret.add(Coord.Y2);
    }
    return ret;
  }

  public Region(Rectangle rect, Page page,
		Source source, Color color, String id) {
    this.fresh = (source == Source.USER);
    this.rect = rect;
    this.page = page;
    this.color = color;
    if (this.color == null) {
      int i = page.getRegions().size();
      if (i < NamedColor.values().length) {
	this.color = NamedColor.values()[i].color;
      } else {
	this.color = Region.generateRandomColor();
      }
    }
    if (id == null) {
      this.id = HasID.getNextIDAndPut(this);
    } else {
      this.id = id.toLowerCase();
      HasID.put(this);
    }
    highlighted = true;
    this.source = source;
    this.page.addRegion(this);
  }

  public Region(Rectangle rect, Page page, Source source) {
    this(rect, page, source, null, null);
  }

  /** Return the minimum Rectangle containing both (x1,y1) and (x2,y2), except
   * clamped to be within the given page.
   */
  public static Rectangle clampRectToPage(
      double x1, double y1, double x2, double y2, Page page) {
    PDRectangle pageBB = page.getPDBBox();
    float px1 = pageBB.getLowerLeftX(), py1 = pageBB.getLowerLeftY(),
          px2 = pageBB.getUpperRightX(), py2 = pageBB.getUpperRightY();
    x1 = Math.max(px1, Math.min(px2, x1));
    y1 = Math.max(py1, Math.min(py2, y1));
    x2 = Math.max(px1, Math.min(px2, x2));
    y2 = Math.max(py1, Math.min(py2, y2));
    return new Rectangle(
      // NOTE: Tabula's Rectangle class insanely swaps the x1/y1 (left/top)
      // parameters in the constructor. Why?!?!
      (float)y1, (float)x1,
      (float)(x2 - x1 + 1.0), (float)(y2 - y1 + 1.0)
    );
  }

  public static Rectangle clampRectToPage(Rectangle r, Page page) {
    double x1 = r.getX(), y1 = r.getY();
    return clampRectToPage(x1, y1, x1 + r.getWidth(), y1 + r.getHeight(), page);
  }

  /** Create an axis-aligned rectangle containing pixel positions (x1,y1) and (x2,y2) on the PDF page page, to be drawn in the specified color, having the specified ID.
   */
  public Region(double x1, double y1, double x2, double y2, 
                Page page, Source source, Color color, String id) {
    this(clampRectToPage(x1,y1,x2,y2,page), page, source, color, id);
  }
  
  /** Create a Region, assigning it the specified color and an arbitrary ID. */
  public Region(double x1, double y1, double x2, double y2,
		Page page, Source source, Color color) {
    this(x1,y1, x2,y2, page, source, color, null);
  }
  
  /** Create a Region, assigning it a random-hued color and an arbitrary ID. */
  public Region(double x1, double y1, double x2, double y2,
		Page page, Source source) {
    this(x1,y1, x2,y2, page, source, null, null);
  }

  public void remove() {
    page.removeRegion(this);
    HasID.remove(this);
  }

  public void raise() {
    page.raiseRegion(this);
  }

  /** Generate a color for a Region.
   * The color should be visible and distinct from other colors so generated.
   * Currently, hues are in the interval [0, 1], saturations [0.5, 1], and 
   * brightnesses [0.5, 1]. This prevents the color from being too close to
   * black or white, but hopefully gives enough different colors that we're not
   * likely to have two regions look the same color.
   */
  public static Color generateRandomColor() {
    return Color.getHSBColor((float)Math.random(),
                             (float)Math.random() / 2.0f + 0.5f,
			     (float)Math.random() / 2.0f + 0.5f);
  }

  public boolean isHighlighted() { return highlighted; }
  public void setHighlighted(boolean h) {
    if (h != highlighted) {
      highlighted = h;
      page.emit(new Page.Event((h ? Page.Event.Type.REGION_HIGHLIGHTED : Page.Event.Type.REGION_UNHIGHLIGHTED), this));
    }
  }

  public boolean isNew() { return fresh; }
  public void setNew(boolean n) {
    if (!n)
      page.emit(new Page.Event(Page.Event.Type.REGION_STOPPED_CHANGING, this));
    fresh = n;
  }

  /** Return true iff this region contains the pixel at coordinates x,y.
   */
  public boolean contains(double x, double y) {
    return rect.contains(x, y);
  }

  /** Paint a rectangle representing this region.
   */
  public void paint(Graphics g) {
    if (highlighted) {
      Color oldColor = g.getColor(); // not sure whether I need to do this

      // first, fill the rectangle with a half-transparent version of the color
      g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(),
                 63));
      g.fillRect((int)getMinX() + 1, (int)getMinY() + 1,
		 Math.max(0,(int)getAbsWidth() - 2),
		 Math.max(0,(int)getAbsHeight() - 2));
      // then, outline the rectangle with the non-transparent version
      g.setColor(color);
      g.drawRect((int)getMinX(), (int)getMinY(),
		 Math.max(0,(int)getAbsWidth() - 1),
		 Math.max(0,(int)getAbsHeight() - 1));

      g.setColor(oldColor);
    }
  }

  /** Return an equivalent Tabula Page object. Note that Tabula "Page"s can be
   * either the whole page or an area of the page. This gets the latter.
   */
  public technology.tabula.Page toTabulaPage() {
    return page.toTabulaPage().getArea(rect);
  }

  /** Return a KQML representation of this region.
   */
  @Override public KQMLObject toKQML() { return toKQML(true); }
  public KQMLObject toKQML(boolean includePage) {
    KQMLPerformative reg;
    if (source == Source.HORIZONTAL) {
      reg = new KQMLPerformative("horizontal");
    } else if (source == Source.VERTICAL) {
      reg = new KQMLPerformative("vertical");
    } else {
      reg = new KQMLPerformative("rectangle");
    }
    reg.setParameter(":id", getID());
    if (includePage)
      reg.setParameter(":page", page.toKQML(false));
    reg.setParameter(":x", Double.toString(getMinX()));
    reg.setParameter(":y", Double.toString(getMinY()));
    if (source != Source.VERTICAL)
      reg.setParameter(":w", Double.toString(getAbsWidth()));
    if (source != Source.HORIZONTAL)
      reg.setParameter(":h", Double.toString(getAbsHeight()));
    for (NamedColor c : NamedColor.values()) {
      if (c.color.equals(color)) {
	reg.setParameter(":color", new KQMLToken("ONT::" + c.toString()));
	break;
      }
    }
    // TODO generic color descriptions for unnamed colors
    return reg;
  }

  /** Construct a region from its KQML representation, or fetch it from its ID.
   */
  public static Region fromKQML(KQMLObject listOrID) throws CWCException, KQMLBadPerformativeException {
    if (listOrID instanceof KQMLList) {
      return fromKQML(new KQMLPerformative((KQMLList)listOrID));
    } else if (listOrID instanceof KQMLPerformative) {
      KQMLPerformative perf = (KQMLPerformative)listOrID;
      KQMLToken idKQML =
	Args.getTypedArgument(perf, ":id", KQMLToken.class,
			      new KQMLToken("nil"));
      String id = idKQML.toString().toLowerCase();
      if (!id.equals("nil")) {
	// this is kind of a shady thing to do
	return fromKQML(idKQML);
      } else {
	double x1 = Args.getTypedArgument(perf, ":x", Double.class);
	double y1 = Args.getTypedArgument(perf, ":y", Double.class);
	double x2 = x1 + Args.getTypedArgument(perf, ":w", Double.class, 1.0);
	double y2 = y1 + Args.getTypedArgument(perf, ":h", Double.class, 1.0);
	KQMLObject pageKQML =
	  Args.getTypedArgument(perf, ":page", KQMLObject.class);
	Page page = Page.fromKQML(pageKQML);
	Color color = null;
	KQMLToken colorKQML =
	  Args.getTypedArgument(perf, ":color", KQMLToken.class, null);
	if (colorKQML != null)
	  color = colorFromKQML(colorKQML);
        return new Region(x1,y1, x2,y2, page, Source.SYSTEM, color);
      }
    } else if (listOrID instanceof KQMLToken) {
      return HasID.get(listOrID.toString(), Region.class);
    } else {
      throw new InvalidArgument("nil", ":region", "list or id", listOrID);
    }
  }

  public static Color colorFromKQML(KQMLToken colorKQML) throws InvalidArgument {
    try {
      NamedColor namedColor =
	Enum.valueOf(NamedColor.class, colorKQML.getName().toUpperCase());
      return namedColor.color;
    } catch (IllegalArgumentException ex) {
      throw new InvalidArgument("rectangle", ":color", "ONT type under ONT::color-val (except black, white, no-color-val, and metallic colors)", colorKQML);
    }
  }

  @Override
  public List<TextMatch> search(Pattern searchPattern) {
    String context = getText();
    List<TextMatch> matches = TextMatch.search(searchPattern, context);
    List<TextMatch> matchesHere = new ArrayList<TextMatch>(matches.size());
    for (TextMatch m : matches) { matchesHere.add(m.inRegion(this)); }
    return matchesHere;
  }

  /** A relationship between two Regions. May be abstract or concrete. A
   * concrete Relation has actual Region IDs in the aID/bID fields, and
   * single IntervalRelations. An abstract Relation may have variables
   * (starting with "?") in the aID/bID fields, and may have null
   * IntervalRelations (standing for "any") or nontrivial sets of
   * IntervalRelations.
   */
  public static class Relation {
    public final String aID;
    public final EnumSet<IntervalRelation> horizontally;
    public final EnumSet<IntervalRelation> vertically;
    public final String bID;

    public Relation(String aID, EnumSet<IntervalRelation> horizontally, EnumSet<IntervalRelation> vertically, String bID) {
      this.aID = aID;
      this.horizontally = horizontally;
      this.vertically = vertically;
      this.bID = bID;
    }
    
    public Relation(String aID, IntervalRelation horizontally, IntervalRelation vertically, String bID) {
      this(aID, EnumSet.of(horizontally), EnumSet.of(vertically), bID);
    }

    /** Return the concrete relation between regions a and b. */
    public static Relation of(Region a, Region b) {
      return new Relation(
        a.getID(),
	IntervalRelation.of(a.getMinX(), a.getMaxX(), b.getMinX(), b.getMaxX()),
	IntervalRelation.of(a.getMinY(), a.getMaxY(), b.getMinY(), b.getMaxY()),
        b.getID()
      );
    }

    /** Is the value of aID or bID a variable? */
    public static boolean isVar(String id) { return id.startsWith("?"); }

    /** Return a new Relation that substitutes aID/bID with their values in the
     * bindings map, if any.
     */
    public Relation substituteVars(Map<String,String> bindings) {
      return new Relation(
        bindings.containsKey(aID) ? bindings.get(aID) : aID,
	horizontally, vertically,
        bindings.containsKey(bID) ? bindings.get(bID) : bID
      );
    }

    static <T extends Enum<T>> boolean unifies(EnumSet<T> a, EnumSet<T> b) {
      if (a == null || b == null) return true;
      EnumSet<T> intersection = a.clone();
      intersection.retainAll(b);
      //System.err.println(a.toString() + " ∩ " + b.toString() + " = " + intersection.toString());
      return !intersection.isEmpty();
    }

    /** Attempt to unify this abstract relation (which must have already-bound
     * vars substituted) with the concrete relation other, while adding any
     * necessary variable bindings to the given bindings map (if non-null).
     */
    public boolean unifyWith(Relation other, Map<String,String> bindings) {
      if (!(unifies(horizontally, other.horizontally) &&
	    unifies(vertically, other.vertically)))
	return false;
      boolean bindA = false;
      boolean bindB = false;
      if (isVar(aID)) {
	bindA = true;
      } else if (!aID.equalsIgnoreCase(other.aID)) {
	return false;
      }
      if (isVar(bID)) {
	bindB = true;
      } else if (!bID.equalsIgnoreCase(other.bID)) {
	return false;
      }
      if (bindings != null) {
	if (bindA) bindings.put(aID, other.aID);
	if (bindB) bindings.put(bID, other.bID);
      }
      return true;
    }
    public boolean unifyWith(Relation other) { return unifyWith(other, null); }

    public static EnumSet<IntervalRelation> getIntervalRelationArgument(
        KQMLPerformative perf, String key) throws CWCException {
      EnumSet<IntervalRelation> s = null;
      KQMLObject o = perf.getParameter(key);
      if (o != null) {
	if (o instanceof KQMLToken) {
	  s = EnumSet.of(IntervalRelation.fromKQML((KQMLToken)o));
	} else if (o instanceof KQMLList) {
	  KQMLList l = (KQMLList)o;
	  s = EnumSet.noneOf(IntervalRelation.class);
	  for (KQMLObject e : l) {
	    if (!(e instanceof KQMLToken))
	      throw new InvalidArgument("relation", key, "token or list of tokens", o);
	    s.add(IntervalRelation.fromKQML((KQMLToken)e));
	  }
	} else {
	  throw new InvalidArgument("relation", key, "token or list of tokens", o);
	}
      }
      return s;
    }
    
    static void setIntervalRelationArgument(
        KQMLPerformative perf, String key, EnumSet<IntervalRelation> val) {
      if (val == null) return;
      KQMLList l = new KQMLList();
      for (IntervalRelation r : val)
	l.add(r.toKQML());
      perf.setParameter(key, (l.size() == 1 ? l.get(0) : l));
    }

    public static Relation fromKQML(KQMLObject o) throws CWCException {
      KQMLPerformative perf = null;
      if (o instanceof KQMLPerformative) {
	perf = (KQMLPerformative)o;
      } else if (o instanceof KQMLList) {
	try {
	  perf = new KQMLPerformative((KQMLList)o);
	} catch (KQMLBadPerformativeException ex) {
	  throw new InvalidArgument("nil", ":relation", "performative", o);
	}
      } else {
	throw new InvalidArgument("nil", ":relation", "performative", o);
      }
      KQMLToken aID = Args.getTypedArgument(perf, ":a", KQMLToken.class);
      EnumSet<IntervalRelation> horizontally =
        getIntervalRelationArgument(perf, ":horizontally");
      EnumSet<IntervalRelation> vertically =
        getIntervalRelationArgument(perf, ":vertically");
      KQMLToken bID = Args.getTypedArgument(perf, ":b", KQMLToken.class);
      // TODO? check that aID and bID are either ?vars or ID known regions
      return new Relation(aID.toString(), horizontally, vertically, bID.toString());
    }

    public KQMLPerformative toKQML() {
      KQMLPerformative reln = new KQMLPerformative("relation");
      reln.setParameter(":a", aID);
      setIntervalRelationArgument(reln, ":horizontally", horizontally);
      setIntervalRelationArgument(reln, ":vertically", vertically);
      reln.setParameter(":b", bID);
      return reln;
    }
  }

  /** An ordering to sort Regions by. */
  public static class Order implements Comparator<Region> {
    /** Where to get the value to sort by. */
    interface Key {
      double of(Region r);
      KQMLObject toKQML();
    }
    /** Order regions based on one coordinate. */
    enum SimpleKey implements Key {
      MIN_X, MIN_Y, MAX_X, MAX_Y, WIDTH, HEIGHT;

      /** Get the value to sort by. */
      @Override
      public double of(Region r) {
	switch (this) {
	  case MIN_X: return r.getMinX();
	  case MIN_Y: return r.getMinY();
	  case MAX_X: return r.getMaxX();
	  case MAX_Y: return r.getMaxY();
	  case WIDTH: return r.getAbsWidth();
	  case HEIGHT: return r.getAbsHeight();
	  default: throw new RuntimeException("WTF java, this is exhaustive.");
	}
      }

      @Override
      public KQMLObject toKQML() {
	return new KQMLToken(name().toLowerCase().replace('_', '-'));
      }

      public static Key fromKQML(KQMLToken k) {
	String s = k.toString().toUpperCase().replace('-', '_');
	// aliases
	switch (s) {
	  case "X":
	  case "X1":
	  case "LEFT":	s = "MIN_X"; break;
	  case "Y":
	  case "Y1":
	  case "TOP":	s = "MIN_Y"; break;
	  case "X2":
	  case "RIGHT":	s = "MAX_X"; break;
	  case "Y2":
	  case "BOTTOM":s = "MAX_Y"; break;
	  case "W":	s = "WIDTH"; break;
	  case "H":	s = "HEIGHT"; break;
	}
	return Enum.valueOf(SimpleKey.class, s);
      }
    }
    /** Order regions in relation to a target region, using an abstract
     * distance metric based on all the coordinates.
     */
    public static class DistanceKey implements Key {
      public final Region target;
      public DistanceKey(Region target) {
	this.target = target;
      }
      @Override
      public double of(Region r) {
	Page page = r.getPage();
	PDRectangle pageBB = page.getPDBBox();
	double pageWidth = pageBB.getWidth();
	double pageHeight = pageBB.getHeight();
	double deltaCenterX = Math.abs(r.getCenterX() - target.getCenterX());
	double deltaCenterY = Math.abs(r.getCenterY() - target.getCenterY());
	double targetWidth = target.getAbsWidth();
	double targetHeight = target.getAbsHeight();
	double deltaWidth = Math.abs(r.getAbsWidth() - targetWidth);
	double deltaHeight = Math.abs(r.getAbsHeight() - targetHeight);
	return deltaCenterX / pageWidth + deltaCenterY / pageHeight +
	       deltaWidth / targetWidth + deltaHeight / targetHeight;
      }
      @Override
      public KQMLObject toKQML() {
	return new KQMLList(new KQMLToken("distance"), target.toKQML());
      }
    }
    /** Order regions in relation to a target color, by Euclidean distance in
     * HSB space.
     */
    public static class ColorKey implements Key {
      public final Color target;
      public ColorKey(Color target) {
	this.target = target;
      }
      @Override
      public double of(Region r) {
	float[] targetHSB =
	  Color.RGBtoHSB(target.getRed(), target.getGreen(), target.getBlue(),
			 null);
	Color test = r.getColor();
	float[] testHSB =
	  Color.RGBtoHSB(test.getRed(), test.getGreen(), test.getBlue(), null);
	float dh = testHSB[0] - targetHSB[0];
	float ds = testHSB[1] - targetHSB[1];
	float db = testHSB[2] - targetHSB[2];
	return Math.sqrt(dh*dh + ds*ds + db*db);
      }
      @Override
      public KQMLObject toKQML() {
	throw new UnsupportedOperationException("TODO?");
      }
    }
    public final Key key;
    /** Direction to sort in. */
    public final boolean ascending;

    public Order(Key key, boolean ascending) {
      this.key = key;
      this.ascending = ascending;
    }

    public KQMLObject toKQML() {
      return new KQMLList(key.toKQML(),
			  new KQMLToken(ascending ? "asc" : "desc"));
    }

    public static Order fromKQML(KQMLList l) {
      if (!(l.size() == 2 &&
	    (l.get(0) instanceof KQMLToken) &&
	    (l.get(1) instanceof KQMLToken)))
	throw new IllegalArgumentException();
      boolean ascending = ascendingFromKQML(l.get(1));
      return new Order(SimpleKey.fromKQML((KQMLToken)l.get(0)), ascending);
    }

    public static boolean ascendingFromKQML(KQMLObject o) {
      switch (o.toString().toUpperCase()) {
	case "ASC":
	case "ASCENDING": return true;
	case "DESC":
	case "DESCENDING": return false;
	default: throw new IllegalArgumentException();
      }
    }

    @Override
    public int compare(Region a, Region b) {
      return Double.compare(key.of(a), key.of(b)) * (ascending ? 1 : -1);
    }
  }
  @Override
  public Comparator<Region> orderFromKQML(KQMLList l) {
    return Order.fromKQML(l);
  }
  @Override
  public String kqmlExpectedForOrder() {
    return "list of two tokens: ({{max|min}-{x|y}|width|height} {asc|desc})";
  }
}
