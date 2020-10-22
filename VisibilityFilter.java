package TRIPS.PDFExtractor;

import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.GeneralPath;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.fontbox.util.BoundingBox;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;
import technology.tabula.PublicRSI;
import technology.tabula.Rectangle;
import technology.tabula.TextElement;
import technology.tabula.Utils;

public class VisibilityFilter extends PageDrawer {
  static final boolean debug = false;
  List<ShownGlyph> allGlyphs;
  Set<ShownGlyph> invisibleGlyphs;
  
  VisibilityFilter(PageDrawerParameters params) throws IOException {
    super(params);
    allGlyphs = new ArrayList<ShownGlyph>();
    invisibleGlyphs = new HashSet<ShownGlyph>();
  }

  @Override
  protected void showGlyph(Matrix textRenderingMatrix, PDFont font, int code, String unicode, Vector displacement) throws IOException {
    if (!isPrintable(unicode)) // TextStripper::writeString skips these
      return;
    //System.err.println(unicode);
    // make a TextPosition the way TextStripper would (which is apparently
    // "deliberately incorrect"?!) so that we can get the rectangle it would
    // use for the TextElement for the glyph
    // see LegacyPDFStreamEngine at large
    PDPage page = getCurrentPage();
    int pageRotation = page.getRotation();
    PDRectangle pageSize = page.getCropBox();
    Matrix translateMatrix;
    if (pageSize.getLowerLeftX() == 0 && pageSize.getLowerLeftY() == 0) {
      translateMatrix = null;
    } else {
      translateMatrix =
        Matrix.getTranslateInstance(
	  -pageSize.getLowerLeftX(), -pageSize.getLowerLeftY());
    }
    // see LegacyPDFStreamEngine::showGlyph()
    PDGraphicsState state = getGraphicsState();
    Matrix ctm = state.getCurrentTransformationMatrix();
    float fontSize = state.getTextState().getFontSize();
    float horizontalScaling =
      state.getTextState().getHorizontalScaling() / 100f;
    Matrix textMatrix = getTextMatrix();
    BoundingBox bbox = font.getBoundingBox();
    // TODO fix bbox lower left Y if < Short.MIN_VALUE
    float glyphHeight = bbox.getHeight() / 2;
    // TODO use capheight when bbox has high values?
    float height =
      (font instanceof PDType3Font) ?
        font.getFontMatrix().transformPoint(0, glyphHeight).y :
	glyphHeight / 1000;
    float displacementX = displacement.getX();
    // TODO vertical font handling?
    float tx = displacementX * fontSize * horizontalScaling;
    float ty = displacement.getY() * fontSize;
    Matrix td = Matrix.getTranslateInstance(tx, ty);
    Matrix nextTextRenderingMatrix = td.multiply(textMatrix).multiply(ctm);
    float nextX = nextTextRenderingMatrix.getTranslateX();
    float nextY = nextTextRenderingMatrix.getTranslateY();
    float dxDisplay = nextX - textRenderingMatrix.getTranslateX();
    float dyDisplay = height * textRenderingMatrix.getScalingFactorY();
    float glyphSpaceToTextSpaceFactor = 1 / 1000f;
    if (font instanceof PDType3Font)
      glyphSpaceToTextSpaceFactor = font.getFontMatrix().getScaleX();
    float spaceWidthText = 0;
    try {
      spaceWidthText = font.getSpaceWidth() * glyphSpaceToTextSpaceFactor;
    } catch (Throwable exception) { /* don't care */ }
    if (spaceWidthText == 0)
      spaceWidthText = font.getAverageFontWidth() * glyphSpaceToTextSpaceFactor * 0.8f;
    if (spaceWidthText == 0)
      spaceWidthText = 1.0f;
    float spaceWidthDisplay =
      spaceWidthText * textRenderingMatrix.getScalingFactorX();
    Matrix translatedTextRenderingMatrix;
    if (translateMatrix == null) {
      translatedTextRenderingMatrix = textRenderingMatrix;
    } else {
      translatedTextRenderingMatrix =
        Matrix.concatenate(translateMatrix, textRenderingMatrix);
      nextX -= pageSize.getLowerLeftX();
      nextY -= pageSize.getLowerLeftY();
    }
    TextPosition tp = new TextPosition(
      pageRotation,
      pageSize.getWidth(), pageSize.getHeight(),
      translatedTextRenderingMatrix,
      nextX, nextY,
      Math.abs(dyDisplay),
      dxDisplay, // individualWidth
      Math.abs(spaceWidthDisplay), // spaceWidth
      unicode,
      new int[] { code }, // charCodes
      font, fontSize,
      (int)(fontSize * textMatrix.getScalingFactorX()) // fontSizeInPt
    );
    // see TextStripper::writeString
    Rectangle bounds = new Rectangle(
      // NOTE: odd argument order
      // NOTE2: we use Tabula's Utils.round() so that we get exactly the same
      // values Tabula gets (and not, say Math.round() like a sane person would
      // use... Utils.round() converts to a *String* and then to a BigDecimal
      // in order to do its rounding to a configurable number of places, (and
      // then converts back to a float), even though BigDecimal can be
      // constructed directly from a double, and even though you can round a
      // double to 2 decimal places just fine with Math.round(d*100)/100.
      // SMDH.)
      Utils.round(tp.getYDirAdj() - tp.getHeightDir(), 2), // top (min-y)
      Utils.round(tp.getXDirAdj(), 2), // left (min-x)
      Utils.round(tp.getWidthDirAdj(), 2), // width
      Utils.round(tp.getHeightDir(), 2) // height
    );
    // check whether this glyph is clipped
    // see PageDrawer::drawGlyph2D et al.
    Area clip = state.getCurrentClippingPath();
    AffineTransform at = textRenderingMatrix.createAffineTransform();
    at.concatenate(font.getFontMatrix().createAffineTransform());
    // ARGH, createGlyph2D is private, and the classes it instantiates are
    // non-public, so we can't just reimplement it
    //GeneralPath drawPath = createGlyph2D(font).getPathForCharacterCode(code);
    //Rectangle2D drawBounds = drawPath.getBounds2D();
    // instead, get the bounding box for the font in general, and translate it
    // to a Rectangle2D.Float (why isn't it already this?!)
    BoundingBox fontBBox = font.getBoundingBox();
    Rectangle2D.Float drawBounds = new Rectangle2D.Float(
      fontBBox.getLowerLeftX(), fontBBox.getLowerLeftY(),
      fontBBox.getWidth(), fontBBox.getHeight()
    );
    Shape transformedPath = at.createTransformedShape(drawBounds);
    Rectangle2D transformedBounds = transformedPath.getBounds2D();
    boolean visible = clip.intersects(transformedBounds);
    if (visible &&
        (transformedBounds.getWidth() > 50 ||
         transformedBounds.getHeight() > 50)) {
      if (debug) System.err.println("suspiciously large glyph; transformedBounds=\n  " + transformedBounds.toString());
      visible = false;
    }
    // make our representation of the glyph
    ShownGlyph glyph = new ShownGlyph(bounds, unicode, visible);
    if (!visible) {
      if (debug) System.err.println("clipped " + glyph.toString() + " against " + pathToString(clip) + " with transform " + at.toString());
      invisibleGlyphs.add(glyph);
    }
    allGlyphs.add(glyph);
    /*if ("2".equals(unicode)) {
      System.err.println("found " + (visible ? "a visible" : "an invisible") + " '2' with transformed bounds\n  " + transformedBounds.toString() + "\nand clip area\n  " + pathToString(clip) + "\nin color " + getGraphics().getColor().toString());
    }*/
    // docs say it does nothing, but just in case...
    super.showGlyph(textRenderingMatrix, font, code, unicode, displacement);
  }

  // see TextStripper::isPrintable
  static boolean isPrintable(String s) {
    if (s == null) return false;
    for (int i = 0; i < s.length(); i++) {
      Character c = s.charAt(i);
      if (isPrintable(c))
	return true;
    }
    return false;
  }

  static boolean isPrintable(Character c) {
    Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
    return !(Character.isISOControl(c) ||
	     block == null || block == Character.UnicodeBlock.SPECIALS);
  }

  // see PDFTextStripper::overlap
  static boolean overlap(float y1, float height1, float y2, float height2) {
    return within(y1, y2, 0.1f) || y2 <= y1 && y2 >= y1 - height1 ||
           y1 <= y2 && y1 >= y2 - height2;
  }
  static boolean within(float first, float second, float variance) {
    return second < first + variance && second > first - variance;
  }

  /* I can't get the transforms to work right for this. Probably not worth it.
  @Override
  public void fillPath(int windingRule) throws IOException {
    PDGraphicsState state = getGraphicsState();
    AffineTransform at =
      state.getCurrentTransformationMatrix().createAffineTransform();
    Shape lp = at.createTransformedShape(getLinePath());
    Rectangle2D bounds = lp.getBounds2D();
    if (state.getCurrentClippingPath().intersects(bounds)) // not clipped
      for (ShownGlyph g : allGlyphs)
	if (g.visible && bounds.intersects(g.bounds)) {
	  // we thought g was visible, but this new path overdraws it
	  System.err.println("overdrew " + g.toString() + " with " + pathToString(lp));
	  g.visible = false;
	  invisibleGlyphs.add(g);
	}
    super.fillPath(windingRule);
  }*/

  /** Return a String representing the shape as an SVG path. */
  public static String pathToString(Shape path) {
    StringBuilder b = new StringBuilder();
    PathIterator iter = path.getPathIterator(null);
    float[] coords = new float[6];
    String[] coordStrs = new String[6];
    while (!iter.isDone()) {
      int segType = iter.currentSegment(coords);
      for (int i = 0; i < 6; i++)
	coordStrs[i] = String.format("%.2f", coords[i]);
      switch (segType) {
	case PathIterator.SEG_MOVETO:
	  b.append("M").
	    append(coordStrs[0]).append(",").append(coordStrs[1]).
	    append(" ");
	  break;
	case PathIterator.SEG_LINETO:
	  b.append("L").
	    append(coordStrs[0]).append(",").append(coordStrs[1]).
	    append(" ");
	  break;
	case PathIterator.SEG_QUADTO:
	  b.append("Q").
	    append(coordStrs[0]).append(",").append(coordStrs[1]).append(" ").
	    append(coordStrs[2]).append(",").append(coordStrs[3]).
	    append(" ");
	  break;
	case PathIterator.SEG_CUBICTO:
	  b.append("C").
	    append(coordStrs[0]).append(",").append(coordStrs[1]).append(" ").
	    append(coordStrs[2]).append(",").append(coordStrs[3]).append(" ").
	    append(coordStrs[4]).append(",").append(coordStrs[5]).
	    append(" ");
	  break;
	case PathIterator.SEG_CLOSE:
	  b.append("Z ");
	  break;
	default:
	  throw new RuntimeException("unknown path segment type: " + segType);
      }
      iter.next();
    }
    return b.toString();
  }

  static class ShownGlyph {
    public final Rectangle bounds;
    public final String unicode;
    public boolean visible;
    public ShownGlyph(Rectangle bounds, String unicode, boolean visible) {
      this.bounds = bounds;
      this.unicode = unicode;
      this.visible = visible;
    }

    @Override
    public boolean equals(Object otherObj) {
      if (!(otherObj instanceof ShownGlyph)) return false;
      ShownGlyph other = (ShownGlyph)otherObj;
      return bounds.equals(other.bounds) && unicode.equals(other.unicode);
    }

    @Override
    public int hashCode() {
      return bounds.hashCode() ^ unicode.hashCode();
    }

    @Override
    public String toString() {
      return "\"" + unicode + "\" @ " +
        Math.round(bounds.getWidth()*100)/100 + "x" +
        Math.round(bounds.getHeight()*100)/100 + "+" +
	Math.round(bounds.getMinX()*100)/100 + "+" +
	Math.round(bounds.getMinY()*100)/100;
    }
  }

  static class Renderer extends PDFRenderer {
    public VisibilityFilter visibilityFilter;
    public Renderer(PDDocument document) { super(document); }
    @Override
    protected PageDrawer createPageDrawer(PageDrawerParameters parameters) throws IOException {
      visibilityFilter = new VisibilityFilter(parameters);
      return visibilityFilter;
    }
  }

  /** Render one page of doc while tracking whether each printable glyph is
   * visible (not obscured by clipping or overdrawing), and make a new version
   * of the corresponding tabula page with any TextElements corresponding to
   * invisible glyphs removed. The original tabula page may be modified.
   */
  public static technology.tabula.Page removeInvisibleTextElements(PDDocument doc, int pageIndex, technology.tabula.Page tabulaPage) {
    try {
      // do the rendering
      Renderer r = new Renderer(doc);
      r.renderImage(pageIndex);
      // get the invisibleGlyphs and TextElements
      Set<ShownGlyph> invisibleGlyphs = r.visibilityFilter.invisibleGlyphs;
      List<TextElement> textElements = tabulaPage.getText();
      int numTextElements = textElements.size();
      // remove each invisible TextElement from the tabulaPage
      for (int i = numTextElements - 1; i >= 0; i--) {
	TextElement e = textElements.get(i);
	// can't use e directly as bounds, because TextElement redefines
	// equals()/hashCode()
	Rectangle bounds = new Rectangle( // NOTE: odd argument order
	  (float)e.getTop(), (float)e.getLeft(),
	  (float)e.getWidth(), (float)e.getHeight()
	);
	ShownGlyph g = new ShownGlyph(bounds, e.getText(), false);
	if (invisibleGlyphs.contains(g)) {
	  if (debug) System.err.println("removing TextElement " + i + ": " + g);
	  textElements.remove(i);
	}
      }
      // redo spatial index, and thus the whole page, since there is no way to
      // remove elements from the spatial index or replace the spatial index of
      // an existing page
      PublicRSI si = new PublicRSI();
      for (TextElement e : textElements) {
	si.add(e);
      }
      tabulaPage = new technology.tabula.Page( // NOTE: odd argument order
        (float)tabulaPage.getTop(),
        (float)tabulaPage.getLeft(),
        (float)tabulaPage.getWidth(),
        (float)tabulaPage.getHeight(),
        tabulaPage.getRotation(),
	tabulaPage.getPageNumber(),
	tabulaPage.getPDPage(),
	textElements,
	tabulaPage.getUnprocessedRulings(),
	tabulaPage.getMinCharWidth(),
	tabulaPage.getMinCharHeight(),
	si
      );
      if (debug) System.err.println("we used to have " + numTextElements + " TextElements on this page; now we have " + tabulaPage.getText().size() + " TextElements");
    } catch (Exception ex) {
      System.err.println("failed to remove invisible text elements:");
      ex.printStackTrace();
    }
    return tabulaPage;
  }
}
