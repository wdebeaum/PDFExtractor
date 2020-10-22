package TRIPS.PDFExtractor;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import TRIPS.util.cwc.WindowConfig;
import TRIPS.KQML.KQMLList;
import TRIPS.KQML.KQMLObject;
import TRIPS.KQML.KQMLPerformative;

/** Menu bar associated with a {@link Document} displayed in a {@link PDFPane}.
 */
public class DocumentMenu extends JToolBar implements ActionListener, PDFPane.Listener, Page.Listener {
  PDFExtractor module;
  PDFPane pane;
  OpenAction open;
  JTextField pageNumber;
  DetectTableAction detect;
  ParseTableAction parse;

  public DocumentMenu(PDFExtractor module, PDFPane pane) {
    super();
    setFloatable(false);
    this.module = module;
    this.pane = pane;
    pane.addPDFPaneListener(this);
    pane.getPage().addPageListener(this);
    open = new OpenAction();
    open.setButton(add(open));
    add(new JLabel("Page: "));
    pageNumber = new JTextField("1", 4);
    pageNumber.addActionListener(this);
    add(pageNumber);
    detect = new DetectTableAction();
    detect.setButton(add(detect));
    parse = new ParseTableAction();
    parse.setButton(add(parse));
    parse.setEnabled(false);
  }

  /** Set the text in the pageNumber field to indicate the current page number.
   */
  void setPageNumber() {
    pageNumber.setText(Integer.toString(pane.getPage().getPageIndex() + 1));
  }

  // ActionListener (pageNumber changed)
  @Override public void actionPerformed(ActionEvent evt) {
    String numStr = pageNumber.getText();
    try {
      pane.setPage(Integer.parseInt(numStr) - 1);
      pane.requestFocusInWindow(); // so pgup/dn works
    } catch (Exception ex) {
      ex.printStackTrace();
      setPageNumber(); // back to what it was
    }
  }

  // PDFPane.Listener
  @Override public void pageDisplayed(Page page, JFrame window) {
    page.addPageListener(this);
    detect.setEnabled(true); // TODO remember whether we detected on this page?
    setPageNumber();
  }
  @Override public void pageClicked(int x, int y, Page page) {/* do nothing */}

  // Page.Listener
  @Override public void pageChanged(Page.Event pageEvent) {
    parse.setEnabled(!pane.getPage().getRegions().isEmpty());
  }

  /** Open another PDF document. */
  public class OpenAction extends ActionWithButton {
    public OpenAction() { super("Open..."); }
    // ActionListener
    @Override public void actionPerformed(ActionEvent evt) {
      try {
	module.chooseAndDisplayPDF();
      } catch (IOException ex) {
	ex.printStackTrace();
      }
    }
  }

  /** Detect table regions on the current page. */
  public class DetectTableAction extends ActionWithButton {
    public DetectTableAction() { super("Detect Table"); }
    // ActionListener
    @Override public void actionPerformed(ActionEvent evt) {
      try {
	Page page = pane.getPage();
	List<Region> regions = page.detectTableRegions();
	setEnabled(false);
	pane.requestFocusInWindow(); // so pgup/dn works
	// report
	KQMLList regionsKQML = new KQMLList();
	for (Region region : regions) {
	  regionsKQML.add(region.toKQML(false));
	}
	KQMLPerformative actionKQML = new KQMLPerformative("detected-table-regions");
	actionKQML.setParameter(":page", page.getID());
	actionKQML.setParameter(":regions", regionsKQML);
	module.report(actionKQML);
      } catch (IOException ex) {
	ex.printStackTrace();
      }
    }
  }

  /** Parse the last region added to this page as a table, and display the
   * table.
   */
  public class ParseTableAction extends ActionWithButton {
    public ParseTableAction() { super("Parse Table"); }
    // ActionListener
    @Override public void actionPerformed(ActionEvent evt) {
      List<Region> regions = pane.getPage().getRegions();
      Table table = new Table(regions.get(regions.size() - 1));
      module.displayTable(table, new WindowConfig());
      pane.requestFocusInWindow(); // so pgup/dn works
      // report
      KQMLObject tableKQML = table.toKQML();
      KQMLPerformative actionKQML = new KQMLPerformative("parsed-table");
      actionKQML.setParameter(":table", tableKQML);
      module.report(actionKQML);
    }
  }
}
