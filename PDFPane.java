package TRIPS.PDFExtractor;

import java.io.File;
import java.io.IOException;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.awt.BasicStroke;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import javax.swing.event.MouseInputListener;
import javax.swing.JComponent;
import javax.swing.JFrame;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;

/** Swing component that shows a PDF {@link Document} a {@link Page} at a time.
 */
public class PDFPane extends JComponent implements KeyListener, MouseInputListener, MouseWheelListener, Page.Listener {
  Document doc;
  PDFRenderer renderer;
  final int numPages;
  int pageIndex;
  boolean isDragging;
  Region currentRegion;
  EnumSet<Region.Coord> currentHandle;
  HashSet<Listener> listeners;
  public PDFPane(File pdfFile, int pageIndex) throws IOException {
    setFocusable(true);
    setOpaque(true);
    setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
    PDDocument pdDoc = PDDocument.load(pdfFile);
    doc = new Document(pdDoc, pdfFile);
    renderer = new PDFRenderer(pdDoc);
    numPages = pdDoc.getPages().getCount();
    listeners = new HashSet<Listener>();
    setPage(pageIndex);
    addKeyListener(this);
    addMouseListener(this);
    addMouseMotionListener(this);
    addMouseWheelListener(this);
  }

  public int getNumPages() { return numPages; }

  public void setPage(int pageIndex) {
    if (pageIndex < 0 || pageIndex >= numPages) {
      throw new ArrayIndexOutOfBoundsException(pageIndex);
    }
    this.pageIndex = pageIndex;
    Page page = doc.getPage(pageIndex);
    // setPreferredSize according to page dims
    PDRectangle bbox = page.getPDBBox();
    setPreferredSize(new Dimension((int)bbox.getWidth(), (int)bbox.getHeight()));
    page.addPageListener(this);
    repaint();
    // NOTE: This does nothing when setPage is called from the constructor,
    // since there are no listeners yet; the caller of the constructor must
    // call emitPageDisplayed() itself after adding listeners
    emitPageDisplayed();
  }

  public void incPage(int pageInc) {
    // try to increment by pageInc
    try {
      setPage(this.pageIndex + pageInc);
    } catch (ArrayIndexOutOfBoundsException e) {
      // fall back to first or last page, depending on direction
      if (pageInc < 0) {
	setPage(0);
      } else {
	setPage(numPages - 1);
      }
    }
  }

  public Page getPage() {
    return doc.getPage(pageIndex);
  }

  /** Get the resize cursor to display when the mouse is at (x,y), or a
   * crosshair cursor when no resize handle is at (x,y). The result will agree
   * with getPage().getRegionAt(x,y).
   */
  public Cursor getCursorAt(int x, int y) {
    Region region = getPage().getRegionAt(x, y);
    if (region == null)
      return Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR);
    EnumSet<Region.Coord> handle = region.getHandleAt((double)x, (double)y);
    if (handle.contains(Region.Coord.X1)) {
      if (handle.contains(Region.Coord.Y1)) {
	return Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR);
      } else if (handle.contains(Region.Coord.Y2)) {
	return Cursor.getPredefinedCursor(Cursor.SW_RESIZE_CURSOR);
      } else {
	return Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR);
      }
    } else if (handle.contains(Region.Coord.X2)) {
      if (handle.contains(Region.Coord.Y1)) {
	return Cursor.getPredefinedCursor(Cursor.NE_RESIZE_CURSOR);
      } else if (handle.contains(Region.Coord.Y2)) {
	return Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR);
      } else {
	return Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR);
      }
    } else {
      if (handle.contains(Region.Coord.Y1)) {
	return Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR);
      } else if (handle.contains(Region.Coord.Y2)) {
	return Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR);
      } else {
	return Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR);
      }
    }
  }

  @Override
  public void paintComponent(Graphics g) {
    try {
      // make a copy of g so that renderPageToGraphics doesn't mess up the
      // transform for us
      Graphics2D g2d = (Graphics2D)(g.create());
      renderer.renderPageToGraphics(pageIndex, g2d);
      List<Region> regions = getPage().getRegions();
      synchronized (regions) {
	if (regions.isEmpty())
	  return;
	Region last = regions.get(regions.size()-1);
	// for non-last regions, set a dashed stroke
	((Graphics2D)g).setStroke(
	  new BasicStroke(
	    // defaults for other stroke properties
	    1.0f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER, 10.0f,
	    // dashed stroke properties
	    new float[]{5.0f, 5.0f}, 0.0f
	  )
	);
	for (Region region : regions) {
	  if (region == last) // for last region, reset to default solid stroke
	    ((Graphics2D)g).setStroke(new BasicStroke());
	  region.paint(g);
	}
      }
    } catch (IOException e) {
      System.err.println(e);
    }
  }
  
  //// KeyListener ////

  @Override public void keyPressed(KeyEvent evt) {
    int pageSet = 0;
    int pageInc = 0;
    switch (evt.getKeyCode()) {
      case KeyEvent.VK_PAGE_UP:
	pageInc = -1;
	break;
      case KeyEvent.VK_PAGE_DOWN:
	pageInc = 1;
	break;
      case KeyEvent.VK_HOME:
	pageSet = 0;
	break;
      case KeyEvent.VK_END:
	pageSet = getNumPages() - 1;
	break;
      default:
	return;
    }
    if (pageInc != 0) {
      incPage(pageInc);
    } else {
      setPage(pageSet);
    }
  }

  @Override public void keyReleased(KeyEvent evt) {/* do nothing */}
  @Override public void keyTyped(KeyEvent evt) {/* do nothing */}

  //// MouseInputListener ////

  @Override public void mouseClicked(MouseEvent evt) { requestFocusInWindow(); }
  @Override public void mouseEntered(MouseEvent evt) {/* do nothing */}
  @Override public void mouseExited(MouseEvent evt) {/* do nothing */}

  @Override public void mousePressed(MouseEvent evt) {
    if (evt.getButton() == MouseEvent.BUTTON1) {
      isDragging = false;
      int x = evt.getX(), y = evt.getY();
      Page page = getPage();
      currentRegion = page.getRegionAt(x,y);
      if (currentRegion == null) { // make a new region
	currentRegion = new Region(x,y, x,y, page, Region.Source.USER);
	currentHandle = EnumSet.of(Region.Coord.X2, Region.Coord.Y2);
      } else { // resize an existing region
	currentHandle = currentRegion.getHandleAt(x, y);
	currentRegion.raise();
      }
    }
  }

  @Override public void mouseReleased(MouseEvent evt) {
    if (evt.getButton() == MouseEvent.BUTTON1 && currentRegion != null) {
      if (currentRegion.getAbsWidth() > 1 && currentRegion.getAbsHeight() > 1) {
	currentRegion.normalize(); // for my own sanity
	currentRegion.setNew(false);
      } else { // degenerate selection, forget it
	currentRegion.remove();
	if (!isDragging) { // we weren't dragging, report a click
	  int x = evt.getX(), y = evt.getY();
	  // but first, if we clicked on exactly one region, raise it to the
	  // top
	  Page page = getPage();
	  List<Region> allRegions = page.getRegions();
	  synchronized (allRegions) {
	    List<Region> clickedRegions = page.getRegionsAt(x, y);
	    if (clickedRegions.size() == 1) {
	      Region clickedRegion = clickedRegions.get(0);
	      clickedRegion.raise();
	    }
	  }
	  emitPageClicked(x, y);
	}
      }
      currentRegion = null;
    }
  }

  @Override public void mouseDragged(MouseEvent evt) {
    isDragging = true;
    if (currentRegion != null) {
      currentRegion.setCoords(currentHandle, evt.getX(), evt.getY());
      evt.getComponent().repaint();
    }
  }

  @Override public void mouseMoved(MouseEvent evt) {
    setCursor(getCursorAt(evt.getX(), evt.getY()));
  }

  //// MouseWheelListener ////

  /** The total number of "clicks" so far in the current storm of
   * trackpad-scrolling "MouseWheelEvent"s.
   */
  double preciseTotalNumClicks = 0;
  /** The time that we last received a "MouseWheelEvent" originating from a
   * trackpad (fractional clicks) instead of a real clicky mouse wheel (whole
   * number of clicks), as measured by System.currentTimeMillis().
   */
  long lastTrackpadScrollEventTime = 0;
  /** Duration in milliseconds to wait for more trackpad-sent
   * "MouseWheelEvent"s before we reset preciseTotalNumClicks to 0 and
   * reconsider whether new MouseWheelEvents might actually be coming from a
   * real clicky mouse wheel.
   */
  final long ABANDON_SCROLL_AFTER = 200;
  /** The number of trackpad-sent "clicks" we must receive in order to actually
   * scroll by one full page (which we would do for one real clicky mouse wheel
   * click).
   */
  final double NUM_TRACKPAD_CLICKS_PER_PAGE = 10.0;

  @Override public void mouseWheelMoved(MouseWheelEvent evt) {
    int numClicks = evt.getWheelRotation();
    double preciseNumClicks = evt.getPreciseWheelRotation();
    //System.err.println("scroll " + numClicks + " clicks (precisely " + preciseNumClicks + " clicks)");
    long time = System.currentTimeMillis();
    long duration = time - lastTrackpadScrollEventTime;
    if (duration > ABANDON_SCROLL_AFTER)
      preciseTotalNumClicks = 0;
    if (preciseTotalNumClicks != 0 || numClicks == 0) {
      // this event was sent from a trackpad, and not a real clicky scroll wheel
      //
      // NOTE: We can't just check that preciseNumClicks is not a whole number;
      // it might just happen to be a whole number even on a trackpad, *and*
      // macOS will report non-whole numbers (as low as 0.1!) even on a real
      // clicky scroll wheel. But you can only get numClicks==0 on a trackpad,
      // and in general you usually do get that at the beginning of the scroll
      // gesture, so we use that as a proxy for "is it a trackpad?". Thank you
      // Microsoft for making a mouse axis that isn't actually an axis but two
      // buttons (three if you count the middle-click button it replaced),
      // Apple for lying about how many times those buttons were clicked, and
      // Sun/Oracle for not making Swing able to explicitly distinguish between
      // trackpad scrolling and clicky mouse wheel scrolling.

      preciseTotalNumClicks += preciseNumClicks;
      // take any whole pages out of preciseTotalNumClicks and put them in
      // numClicks
      numClicks = (int)(preciseTotalNumClicks / NUM_TRACKPAD_CLICKS_PER_PAGE);
      preciseTotalNumClicks -= numClicks * NUM_TRACKPAD_CLICKS_PER_PAGE;
      // remember that we got an event from the trackpad
      lastTrackpadScrollEventTime = time;
    }
    if (numClicks != 0)
      incPage(numClicks);
  }

  //// Page.Listener ////

  @Override
  public void pageChanged(Page.Event evt) {
    if (evt.getPage().pageIndex == pageIndex) {
      repaint();
    }
  }

  //// PDFPane.Listener ////

  /** Listener for when a {@link Page} is displayed in a {@link PDFPane} in a
   * window.
   */
  public interface Listener {
    void pageDisplayed(Page page, JFrame window);
    void pageClicked(int x, int y, Page page);
  }

  public void addPDFPaneListener(Listener l) { listeners.add(l); }

  public void emitPageDisplayed() {
    Page page = getPage();
    JFrame window = (JFrame)getTopLevelAncestor();
    // re-pack window (if it's there yet) for different-size pages
    if (window != null)
      window.pack();
    for (Listener l : listeners) {
      l.pageDisplayed(page, window);
    }
  }

  public void emitPageClicked(int x, int y) {
    Page page = getPage();
    JFrame window = (JFrame)getTopLevelAncestor();
    for (Listener l : listeners) {
      l.pageClicked(x, y, page);
    }
  }
}
