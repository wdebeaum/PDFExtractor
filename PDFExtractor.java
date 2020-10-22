package TRIPS.PDFExtractor;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import technology.tabula.RectangularTextContainer;
import TRIPS.KQML.*;
import TRIPS.util.cwc.Args;
import TRIPS.util.cwc.CWCException;
import TRIPS.util.cwc.InvalidArgument;
import TRIPS.util.cwc.InvalidArgumentCombo;
import TRIPS.util.cwc.MissingArgument;
import TRIPS.util.cwc.StandardCWCModule;
import TRIPS.util.cwc.SwingWindowManager;
import TRIPS.util.cwc.UnknownAction;
import TRIPS.util.cwc.WindowConfig;

/**
 * PDFExtractor - semi-automatically extracts table data from PDFs
 */
public class PDFExtractor extends StandardCWCModule implements PDFPane.Listener, Page.Listener, TableSelectionListener {
  boolean standalone;
  String curDir;
  Map<File, PDFPane> pdfFile2pane;
  Map<Table, SpanTable> tableModel2view;
  Map<Table, TableEditMenu> tableModel2menu;

  public PDFExtractor(String[] argv) {
    super(argv);
    standalone = Arrays.asList(argv).contains("-standalone");
    curDir = (standalone ? System.getProperty("user.dir") : System.getenv("TRIPS_BASE"));
    pdfFile2pane = new HashMap<File, PDFPane>();
    tableModel2view = new HashMap<Table, SpanTable>();
    tableModel2menu = new HashMap<Table, TableEditMenu>();
  }

  private void setLAF() {
    // try to use a better L&F, because Java's default file chooser is *awful*
    // list of look-and-feels in order of my preference
    List<String> lafcns = new ArrayList<String>(4);
    lafcns.add("com.apple.laf.AquaLookAndFeel");
    lafcns.add("com.sun.java.swing.plaf.windows.WindowsLookAndFeel");
    // I like how GTK looks better than Motif generally, but GTK makes the
    // buttons only have outlines on hover. At least Motif buttons look like
    // buttons all the time.
    lafcns.add("com.sun.java.swing.plaf.motif.MotifLookAndFeel");
    lafcns.add("com.sun.java.swing.plaf.gtk.GTKLookAndFeel");
    // add the "system" L&F just in case it's not one of the above (it's
    // probably still better than Metal)
    String slafcn = UIManager.getSystemLookAndFeelClassName();
    if (slafcn.equals("javax.swing.plaf.metal.MetalLookAndFeel")) {
      lafcns.add(slafcn); // put Metal at the end
    } else { // as long as it's not Metal, put it first
      lafcns.add(0, slafcn);
    }
    for (String lafcn : lafcns) {
      try {
	UIManager.setLookAndFeel(lafcn);
	System.err.println("Set look and feel to " + lafcn);
	break;
      } catch (Exception ex) {
	System.err.println("Failed to set look and feel to " + lafcn);
      }
    }
  }

  @Override
  public void init() {
    name = "PDFExtractor";
    setLAF();
    windowManager = new SwingWindowManager(this);
    if (standalone) {
      handleCommonParameters();
      // fake a connection so e.g. send doesn't cause errors
      try {
	out = new PrintWriter("/dev/null");
	in = new KQMLReader(new FileReader("/dev/null"));
      } catch (IOException ex) {
	System.err.println("Can't open /dev/null?!? Oh well, falling back on stdin/stdout.");
	out = new PrintWriter(System.out, true);
	in  = new KQMLReader(System.in);
      }
      dispatcher = new KQMLDispatcher(this, in);
      // put up a file chooser
      try {
	int status = chooseAndDisplayPDF();
	if (status != JFileChooser.APPROVE_OPTION) // cancel or error (close)
	  System.exit(1);
      } catch (IOException ex) {
	System.err.println("Error opening PDF file:");
	ex.printStackTrace();
	System.exit(1);
      }
    } else { // not standalone; connect to TRIPS
      super.init();
      ready();
      // start LearningGUI module too unless we "connected" to stdin/stdout
      if (autoConnect)
	LearningGUI.main(argv);
    }
  }

  @Override
  public void subscribe() throws IOException {
    super.subscribe();
    send(KQMLPerformative.fromString(
      "(subscribe :content (request &key :content (display . *)))"));
    send(KQMLPerformative.fromString(
      "(subscribe :content (request &key :content (display-table . *)))"));
    send(KQMLPerformative.fromString(
      "(subscribe :content (request &key :content (parse-table . *)))"));
    send(KQMLPerformative.fromString(
      "(subscribe :content (request &key :content (edit-table . *)))"));
    send(KQMLPerformative.fromString(
      "(subscribe :content (request &key :content (auto-split-columns . *)))"));
    send(KQMLPerformative.fromString(
      "(subscribe :content (request &key :content (auto-merge-cells . *)))"));
    send(KQMLPerformative.fromString(
      "(subscribe :content (request &key :content (save-table . *)))"));
    send(KQMLPerformative.fromString(
      "(subscribe :content (request &key :content (get-history . *)))"));
    send(KQMLPerformative.fromString(
      "(subscribe :content (request &key :content (detect-table-regions . *)))"));
    send(KQMLPerformative.fromString(
      "(subscribe :content (request &key :content (select . *)))"));
    send(KQMLPerformative.fromString(
      "(subscribe :content (request &key :content (deselect . *)))"));
    send(KQMLPerformative.fromString(
      "(subscribe :content (request &key :content (detect-paragraph-regions . *)))"));
    send(KQMLPerformative.fromString(
      "(subscribe :content (request &key :content (get-content . *)))"));
    send(KQMLPerformative.fromString(
      "(subscribe :content (request &key :content (detect-ruling-regions . *)))"));
    send(KQMLPerformative.fromString(
      "(subscribe :content (request &key :content (relate-regions . *)))"));
    send(KQMLPerformative.fromString(
      "(subscribe :content (request &key :content (relate-all-regions . *)))"));
    send(KQMLPerformative.fromString(
      "(subscribe :content (request &key :content (find-related-regions . *)))"));
    send(KQMLPerformative.fromString(
      "(subscribe :content (request &key :content (find-similar-regions . *)))"));
    send(KQMLPerformative.fromString(
      "(subscribe :content (request &key :content (search . *)))"));
  }

  @Override
  public void restart() {
    super.restart();
    HasID.clear();
  }

  @Override
  public void declareCapabilities() throws IOException {
    send(KQMLPerformative.fromString(
      "(tell :content (define-service :name display :component " + name +
      " :input (" +
        "(input" + 
        " :name file" +
	" :gloss \"PDF file to display\"" +
	" :id-code FILE" +
	" :format ont::file" +
	" :requirements :required) " +
	"(input" +
	" :name page" +
	" :gloss \"index of page in file to display\"" +
	" :id-code PAGE" +
	" :format number" +
	" :requirements :optional)" +
        ")" +
      " :output nil))"));
    send(KQMLPerformative.fromString(
      "(tell :content (define-service :name display-table :component " + name +
      " :input (" +
        "(input" + 
        " :name table" +
	" :gloss \"ID of table to display\"" +
	" :id-code TABLE" +
	" :format ont::symbol" +
	" :requirements :required)" +
        ")" +
      " :output nil))"));
    send(KQMLPerformative.fromString(
      "(tell :content (define-service :name parse-table :component " + name +
      " :input (" +
	"(input" +
	" :name region" +
	" :gloss \"PDF page region to parse as a table\"" +
	" :id-code REGION" +
	" :format ont::symbol" +
	" :requirements :required)" +
        ")" +
      " :output (" +
        "(output" +
	" :name table" +
	" :gloss \"parsed table structure\"" +
	" :id-code DATA" +
	" :format ont::list)" +
        ")" +
      "))"));
    send(KQMLPerformative.fromString(
      "(tell :content (define-service :name edit-table :component " + name +
      " :input (" +
	"(input" +
	" :name table" +
	" :gloss \"ID of table to modify\"" +
	" :id-code TABLE" +
	" :format ont::symbol" +
	" :requirements :required) " +
	"(input" +
	" :name edit" +
	" :gloss \"description of the edit to make\"" +
	" :id-code EDIT" +
	" :format ont::list" +
	" :requirements :required)" +
        ")" +
      " :output (" +
        "(output" +
	" :name table" +
	" :gloss \"edited table structure\"" +
	" :id-code DATA" +
	" :format ont::list)" +
        "(output" +
	" :name edit" +
	" :gloss \"specific edit made\"" +
	" :id-code EDIT" +
	" :format ont::list)" +
        ")" +
      "))"));
    send(KQMLPerformative.fromString(
      "(tell :content (define-service :name auto-split-columns :component " + name +
      " :input (" +
	"(input" +
	" :name table" +
	" :gloss \"ID of table to modify\"" +
	" :id-code TABLE" +
	" :format ont::symbol" +
	" :requirements :required) " +
        ")" +
      " :output (" +
        "(output" +
	" :name table" +
	" :gloss \"edited table structure\"" +
	" :id-code DATA" +
	" :format ont::list) " +
        "(output" +
	" :name edits" +
	" :gloss \"specific edits made\"" +
	" :id-code EDITS" +
	" :format ont::list)" +
       ")" +
      "))"));
    send(KQMLPerformative.fromString(
      "(tell :content (define-service :name auto-merge-cells :component " + name +
      " :input (" +
	"(input" +
	" :name table" +
	" :gloss \"ID of table to modify\"" +
	" :id-code TABLE" +
	" :format ont::symbol" +
	" :requirements :required) " +
        ")" +
      " :output (" +
        "(output" +
	" :name table" +
	" :gloss \"edited table structure\"" +
	" :id-code DATA" +
	" :format ont::list) " +
        "(output" +
	" :name edits" +
	" :gloss \"specific edits made\"" +
	" :id-code EDITS" +
	" :format ont::list)" +
       ")" +
      "))"));
    send(KQMLPerformative.fromString(
      "(tell :content (define-service :name save-table :component " + name +
      " :input (" +
	"(input" +
	" :name table" +
	" :gloss \"ID of table to save\"" +
	" :id-code TABLE" +
	" :format ont::symbol" +
	" :requirements :required) " +
	"(input" +
	" :name file" +
	" :gloss \"file to save to\"" +
	" :id-code FILE" +
	" :format ont::list" +
	" :requirements :optional)" +
        ")" +
      " :output (" +
        "(output" +
	" :name file" +
	" :gloss \"file that was saved to\"" +
	" :id-code FILE" +
	" :format ont::list)" +
        ")" +
      "))"));
    send(KQMLPerformative.fromString(
      "(tell :content (define-service :name get-history :component " + name +
      " :input (" +
        "(input" +
	" :name of" +
	" :gloss \"ID of table to get the history of\"" +
	" :id-code TABLE" +
	" :format ont::symbol" +
	" :requirements :required)" +
	")" +
      " :output (" +
        "(output" +
	" :name origin" +
	" :gloss \"the region the table was originally parsed from\"" +
	" :id-code REGION" +
	" :format ont::list) " +
	"(output" +
	" :name edits" +
	" :gloss \"the list of edits made to the table\"" +
	" :id-code EDITS" +
	" :format ont::list) " +
	"(output" +
	" :name num-rows" +
	" :gloss \"the current number of rows in the table\"" +
	" :id-code NUM-ROWS" +
	" :format number) " +
	"(output" +
	" :name num-columns" +
	" :gloss \"the current number of columns in the table\"" +
	" :id-code NUM-COLUMNS" +
	" :format number)" +
	")" +
      "))"));
    send(KQMLPerformative.fromString(
      "(tell :content (define-service :name detect-table-regions :component " + name +
      " :input (" +
	"(input" +
	" :name page" +
	" :gloss \"page to detect table regions in\"" +
	" :id-code PAGE" +
	" :format (or ont::list ont::symbol)" +
	" :requirements :required)" +
        ")" +
      " :output (" +
        "(output" +
	" :name regions" +
	" :gloss \"detected table region structures\"" +
	" :id-code REGION" +
	" :format ont::list)" +
        ")" +
      "))"));
    send(KQMLPerformative.fromString(
      "(tell :content (define-service :name select :component " + name +
      " :input (" +
        "(input" + 
        " :name what" +
	" :gloss \"thing to select\"" +
	" :id-code THING" +
	" :format ont::list" +
	" :requirements :required)" +
        ")" +
      " :output nil))"));
    send(KQMLPerformative.fromString(
      "(tell :content (define-service :name deselect :component " + name +
      " :input (" +
        "(input" + 
        " :name what" +
	" :gloss \"thing to deselect\"" +
	" :id-code THING" +
	" :format ont::list" +
	" :requirements :required)" +
        ")" +
      " :output nil))"));
    send(KQMLPerformative.fromString(
      "(tell :content (define-service :name detect-paragraph-regions :component " + name +
      " :input (" +
	"(input" +
	" :name page" +
	" :gloss \"page to detect paragraph regions in\"" +
	" :id-code PAGE" +
	" :format (or ont::list ont::symbol)" +
	" :requirements :required)" +
        ")" +
      " :output (" +
        "(output" +
	" :name regions" +
	" :gloss \"detected paragraph region structures\"" +
	" :id-code REGION" +
	" :format ont::list)" +
        ")" +
      "))"));
    send(KQMLPerformative.fromString(
      "(tell :content (define-service :name get-content :component " + name +
      " :input (" +
	"(input" +
	" :name of" +
	" :gloss \"table cell selection or ID of region to get the content of\"" +
	" :id-code CONTAINER" +
	" :format (or ont::list ont::symbol)" +
	" :requirements :required) " +
	"(input" +
	" :name format" +
	" :gloss \"format of the content, text/plain or text/html\"" +
	" :id-code FORMAT" +
	" :format ont::string" +
	" :requirements :optional)" +
        ")" +
      " :output (" +
        "(output" +
	" :name text" +
	" :gloss \"text of the paragraph\"" +
	" :id-code TEXT" +
	" :format ont::string)" +
        ")" +
      "))"));
    send(KQMLPerformative.fromString(
      "(tell :content (define-service :name detect-ruling-regions :component " + name +
      " :input (" +
	"(input" +
	" :name page" +
	" :gloss \"page to detect rulings in\"" +
	" :id-code PAGE" +
	" :format (or ont::list ont::symbol)" +
	" :requirements :required)" +
        ")" +
      " :output (" +
        "(output" +
	" :name regions" +
	" :gloss \"detected horizontal or vertical ruling structures\"" +
	" :id-code REGION" +
	" :format ont::list)" +
        ")" +
      "))"));
    send(KQMLPerformative.fromString(
      "(tell :content (define-service :name relate-regions :component " + name +
      " :input (" +
	"(input" +
	" :name a" +
	" :gloss \"ID of first region to relate\"" +
	" :id-code REGION" +
	" :format ont::symbol" +
	" :requirements :required) " +
	"(input" +
	" :name b" +
	" :gloss \"ID of second region to relate\"" +
	" :id-code REGION" +
	" :format ont::symbol" +
	" :requirements :required)" +
        ")" +
      " :output (" +
        "(output" +
	" :name horizontally" +
	" :gloss \"relationship between the horizontal intervals of a and b\"" +
	" :id-code INTERVAL-RELATION" +
	" :format ont::symbol) " +
        "(output" +
	" :name vertically" +
	" :gloss \"relationship between the vertictal intervals of a and b\"" +
	" :id-code INTERVAL-RELATION" +
	" :format ont::symbol)" +
        ")" +
      "))"));
    send(KQMLPerformative.fromString(
      "(tell :content (define-service :name relate-all-regions :component " + name +
      " :input (" +
	"(input" +
	" :name page" +
	" :gloss \"page to relate the regions of\"" +
	" :id-code PAGE" +
	" :format (or ont::list ont::symbol)" +
	" :requirements :required)" +
        ")" +
      " :output (" +
        "(output" +
	" :name relation" +
	" :gloss \"relation between each pair of regions\"" +
	" :id-code REGION-RELATION" +
	" :format ont::list)" +
        ")" +
      "))"));
    send(KQMLPerformative.fromString(
      "(tell :content (define-service :name find-related-regions :component " + name +
      " :input (" +
        "(input" +
	" :name horizontally" +
	" :gloss \"relationship between the horizontal intervals of a and b\"" +
	" :id-code INTERVAL-RELATION" +
	" :format ont::symbol" +
	" :requirements :optional) " +
        "(input" +
	" :name vertically" +
	" :gloss \"relationship between the vertictal intervals of a and b\"" +
	" :id-code INTERVAL-RELATION" +
	" :format ont::symbol" +
	" :requirements :optional) " +
	"(input" +
	" :name region" +
	" :gloss \"ID of second region (b) to relate\"" +
	" :id-code REGION" +
	" :format ont::symbol" +
	" :requirements :required) " +
	"(input" +
	" :name order-by" +
	" :gloss \"sorting order for results\"" +
	" :id-code ORDER" +
	" :format ont::list" +
	" :requirements :optional) " +
	"(input" +
	" :name limit" +
	" :gloss \"maximum number of results to return\"" +
	" :id-code LIMIT" +
	" :format ont::number" +
	" :requirements :optional) " +
	"(input" +
	" :name soft-limit" +
	" :gloss \"approximate maximum number of results to return\"" +
	" :id-code SOFT-LIMIT" +
	" :format ont::number" +
	" :requirements :optional)" +
        ")" +
      " :output (" +
        "(output" +
	" :name regions" +
	" :gloss \"related (a) regions\"" +
	" :id-code REGION" +
	" :format ont::list)" +
        ")" +
      "))"));
    send(KQMLPerformative.fromString(
      "(tell :content (define-service :name find-similar-regions :component " + name +
      " :input (" +
	"(input" +
	" :name region" +
	" :gloss \"description of the target region\"" +
	" :id-code REGION" +
	" :format ont::list" +
	" :requirements :required) " +
	"(input" +
	" :name limit" +
	" :gloss \"maximum number of results to return\"" +
	" :id-code LIMIT" +
	" :format ont::number" +
	" :requirements :optional) " +
	"(input" +
	" :name soft-limit" +
	" :gloss \"approximate maximum number of results to return\"" +
	" :id-code SOFT-LIMIT" +
	" :format ont::number" +
	" :requirements :optional)" +
        ")" +
      " :output (" +
        "(output" +
	" :name regions" +
	" :gloss \"existing regions similar to the target region\"" +
	" :id-code REGION" +
	" :format ont::list)" +
        ")" +
      "))"));
    send(KQMLPerformative.fromString(
      "(tell :content (define-service :name search :component " + name +
      " :input (" +
        "(input" +
	" :name for" +
	" :gloss \"text to search for\"" +
	" :id-code TEXT" +
	" :format ont::string" +
	" :requirements :required) " +
        "(input" +
	" :name in" +
	" :gloss \"place to search in\"" +
	" :id-code PLACE" +
	" :format ont::symbol" +
	" :requirements :required)" +
	"(input" +
	" :name order-by" +
	" :gloss \"sorting order for results\"" +
	" :id-code ORDER" +
	" :format ont::list" +
	" :requirements :optional) " +
	"(input" +
	" :name limit" +
	" :gloss \"maximum number of results to return\"" +
	" :id-code LIMIT" +
	" :format ont::number" +
	" :requirements :optional) " +
	"(input" +
	" :name soft-limit" +
	" :gloss \"approximate maximum number of results to return\"" +
	" :id-code SOFT-LIMIT" +
	" :format ont::number" +
	" :requirements :optional)" +
        ")" +
      " :output (" +
        "(output" +
	" :name matches" +
	" :gloss \"text match structures\"" +
	" :id-code MATCH" +
	" :format ont::list)" +
	")" +
      "))"));
  }

  @Override
  public boolean receiveRequest(KQMLPerformative msg, String verb, KQMLPerformative content) throws CWCException, IOException {
    if (verb.equals("detect-table-regions")) {
      KQMLObject pageKQML = Args.getTypedArgument(content, ":page", KQMLObject.class, null);
      KQMLObject regionKQML = Args.getTypedArgument(content, ":region", KQMLObject.class, null);
      if (pageKQML == null && regionKQML == null)
	throw new InvalidArgumentCombo("you must specify either :page or :region");
      Page page;
      Region within = null;
      if (regionKQML == null) {
	page = Page.fromKQML(pageKQML);
      } else {
	within = Region.fromKQML(regionKQML);
	page = within.getPage();
	if (pageKQML != null) {
	  Page unusedPage = Page.fromKQML(pageKQML);
	  if (page != unusedPage)
	    throw new InvalidArgumentCombo("both :page and :region specified, but region has different page. Use one or the other.");
	}
      }
      List<Region> regions = page.detectTableRegions(within);
      KQMLList regionsKQML = new KQMLList();
      for (Region region : regions) {
	regionsKQML.add(region.toKQML(false));
      }
      answer(msg, ":regions", regionsKQML);
    } else if (verb.equals("detect-paragraph-regions")) {
      KQMLObject pageKQML = Args.getTypedArgument(content, ":page", KQMLObject.class);
      Page page = Page.fromKQML(pageKQML);
      List<Region> regions = page.detectParagraphRegions();
      KQMLList regionsKQML = new KQMLList();
      for (Region region : regions) {
	regionsKQML.add(region.toKQML(false));
      }
      answer(msg, ":regions", regionsKQML);
    } else if (verb.equals("detect-ruling-regions")) {
      KQMLObject pageKQML = Args.getTypedArgument(content, ":page", KQMLObject.class);
      Page page = Page.fromKQML(pageKQML);
      List<Region> regions = page.detectRulingRegions();
      KQMLList regionsKQML = new KQMLList();
      for (Region region : regions) {
	regionsKQML.add(region.toKQML(false));
      }
      answer(msg, ":regions", regionsKQML);
    } else if (verb.equals("get-content")) {
      KQMLObject of = content.getParameter(":of");
      String format = Args.getTypedArgument(content, ":format", String.class, "text/plain").toLowerCase();
      RectangularTextContainer rtc = null;
      if (of instanceof KQMLToken) {
	Region region = HasID.get(of.toString(), Region.class);
	if (region.rect instanceof RectangularTextContainer)
	  rtc = (RectangularTextContainer)region.rect;
      } else if (of instanceof KQMLList &&
                 ((KQMLList)of).size() > 1 &&
		 ((KQMLList)of).get(0) instanceof KQMLToken &&
		 ((KQMLList)of).get(0).toString().equalsIgnoreCase("cell")) {
	TableSelection cs = TableSelection.fromKQML(of);
	rtc = cs.table.getCellAt(cs.firstRow, cs.firstCol);
      } else {
	throw new InvalidArgument(content, ":of", "region ID or table cell");
      }
      String contentStr;
      if (rtc == null) {
	contentStr = "";
      } else if (format.equals("text/plain")) {
	contentStr = Cell.getTextOf(rtc);
      } else if (format.equals("text/html")) {
	HTMLBuilder html = Cell.getHTMLOf(rtc);
	contentStr = html.toFragmentString();
      } else {
	throw new InvalidArgument(content, ":format", "text/plain or text/html");
      }
      answer(msg, ":content", new KQMLString(contentStr));
    } else if (verb.equals("relate-regions")) {
      KQMLToken aID = Args.getTypedArgument(content, ":a", KQMLToken.class);
      Region a = HasID.get(aID.toString(), Region.class);
      KQMLToken bID = Args.getTypedArgument(content, ":b", KQMLToken.class);
      Region b = HasID.get(bID.toString(), Region.class);
      KQMLPerformative reln = Region.Relation.of(a, b).toKQML();
      // TODO? include information on regions that are between a and b
      reln.toList().set(0, new KQMLToken("answer")); // set verb (cheating)
      report(msg, reln);
    } else if (verb.equals("relate-all-regions")) {
      KQMLObject pageKQML = Args.getTypedArgument(content, ":page", KQMLObject.class);
      Page page = Page.fromKQML(pageKQML);
      List<Region> regions = page.getRegions();
      KQMLList relns = new KQMLList();
      synchronized (regions) {
	for (Region a : regions)
	  for (Region b : regions)
	    if (a != b)
	      relns.add(Region.Relation.of(a, b).toKQML());
      }
      answer(msg, ":relations", relns);
    } else if (verb.equals("find-related-regions")) {
      EnumSet<IntervalRelation> horizontally =
	Region.Relation.getIntervalRelationArgument(content, ":horizontally");
      EnumSet<IntervalRelation> vertically =
	Region.Relation.getIntervalRelationArgument(content, ":vertically");
      String bID =
	Args.getTypedArgument(content, ":region", KQMLToken.class).toString();
      Region b = HasID.get(bID, Region.class);
      ResultSet<Region> regions = new ResultSet<Region>();
      Region.Relation suchThat =
	new Region.Relation("?x", horizontally, vertically, bID);
      //System.err.println("suchThat = " + suchThat.toKQML().toString());
      List<Region> bPageRegions = b.getPage().getRegions();
      synchronized (bPageRegions) {
	for (Region a : bPageRegions) {
	  if (a == b) continue;
	  Region.Relation reln = Region.Relation.of(a, b);
	  if (suchThat.unifyWith(reln)) {
	    //System.err.println("unifies with " + reln.toKQML().toString());
	    regions.add(a);
	  }
	}
      }
      regions.setSelectArguments(content);
      regions.select();
      answer(msg, ":regions", regions.toKQML());
    } else if (verb.equals("find-similar-regions")) {
      KQMLPerformative targetKQML =
	Args.getTypedArgument(content, ":region", KQMLPerformative.class);
      KQMLToken colorKQML =
        Args.getTypedArgument(targetKQML, ":color", KQMLToken.class, null);
      Region target = null;
      Page page = null;
      Region.Order orderBy = null;
      if (colorKQML != null &&
	  targetKQML.getParameter(":id") == null &&
	  targetKQML.getParameter(":x") == null) {
	// specified target region has only color and not ID or coords
	// go by color
	Color targetColor = Region.colorFromKQML(colorKQML);
	KQMLObject pageKQML =
	  Args.getTypedArgument(targetKQML, ":page", KQMLObject.class);
	page = Page.fromKQML(pageKQML);
	orderBy =
	  new Region.Order(new Region.Order.ColorKey(targetColor), true);
      } else { // go by coords
	target = Region.fromKQML(targetKQML);
	page = target.getPage();
	// don't keep the target region as a real region if we just made it and
	// didn't look it up by ID
	if (targetKQML.getParameter(":id") == null)
	  target.remove();
	// order by ascending distance from target
	orderBy =
	  new Region.Order(new Region.Order.DistanceKey(target), true);
      }
      // make a copy of the list regions on the page so we don't mess up the
      // original's order
      List<Region> origRegions = page.getRegions();
      List<Region> pageRegions;
      synchronized (origRegions) {
	pageRegions = new ArrayList<Region>(origRegions);
      }
      // make sure we don't return the target region as the most similar to
      // itself
      pageRegions.remove(target);
      // make the result set and select from it according to orderBy and any
      // limit args
      ResultSet<Region> regions =
        new ResultSet<Region>(pageRegions);
      regions.setSelectArguments(content, orderBy);
      regions.select();
      // add :distance to each region KQML
      KQMLList regionsKQML = regions.toKQML();
      int i = 0;
      for (KQMLObject regionObj : regionsKQML) {
	if (regionObj instanceof KQMLPerformative) {
	  KQMLPerformative regionPerf = (KQMLPerformative)regionObj;
	  double dist = orderBy.key.of(regions.data.get(i));
	  regionPerf.setParameter(":distance",
	    new KQMLToken(Double.toString(dist)));
	} else {
	  throw new RuntimeException("region KQML isn't a KQMLPerformative (wat?), can't add :distance");
	}
	i++;
      }
      answer(msg, ":regions", regionsKQML);
    } else if (verb.equals("search")) {
      String searchStr = Args.getTypedArgument(content, ":for", String.class);
      String inID =
	Args.getTypedArgument(content, ":in", KQMLToken.class).toString();
      TextMatch.Searchable in =
	HasID.get(inID, TextMatch.Searchable.class);
      ResultSet<TextMatch> matches =
        new ResultSet<TextMatch>(in.search(searchStr), content);
      matches.select();
      answer(msg, ":matches", matches.toKQML());
    } else if (verb.equals("display")) {
      KQMLPerformative file = interpretFileArg(content, null);
      int page = Args.getTypedArgument(content, ":page", Integer.class, 0);
      String filename = Args.getTypedArgument(file, ":name", String.class);
      displayPDF(new File(filename), page, new WindowConfig(content));
    } else if (verb.equals("display-table")) {
      KQMLObject tableKQML = Args.getTypedArgument(content, ":table", KQMLObject.class);
      Table table = Table.fromKQML(tableKQML);
      displayTable(table, new WindowConfig(content));
    } else if (verb.equals("parse-table")) {
      KQMLToken regionID = Args.getTypedArgument(content, ":region", KQMLToken.class);
      Region region = HasID.get(regionID.toString(), Region.class);
      parseTable(msg, region);
    } else if (verb.equals("edit-table")) {
      KQMLToken tableID = Args.getTypedArgument(content, ":table", KQMLToken.class);
      Table table = HasID.get(tableID.toString(), Table.class);
      KQMLPerformative editKQML = Args.getTypedArgument(content, ":edit", KQMLPerformative.class);
      try {
	String editVerb = editKQML.getVerb().toLowerCase();
	if (editVerb.equals("undo") || editVerb.equals("redo")) {
	  int numArgs = editKQML.toList().size() - 1;
	  if (numArgs != 0) {
	    KQMLPerformative reason =
	      new KQMLPerformative("invalid-argument-count");
	    reason.setParameter(":operator", editVerb);
	    reason.setParameter(":expected", new KQMLString("0"));
	    reason.setParameter(":got", Integer.toString(numArgs));
	    throw new CWCException("failed-to-interpret", reason);
	  }
	  if (editVerb.equals("undo")) {
	    Table.Edit edit = table.undo();
	    editKQML = new KQMLPerformative("undo");
	    // NOTE: using toList() like this is cheating a little
	    editKQML.toList().add(edit.toKQML());
	  } else {
	    Table.Edit edit = table.redo();
	    editKQML = (KQMLPerformative)edit.toKQML();
	  }
	} else { // plain edit
	  Table.Edit edit = table.editFromKQML(editKQML);
	  table.edit(edit);
	}
	KQMLPerformative ans = new KQMLPerformative("answer");
	ans.setParameter(":table", table.toKQML());
	ans.setParameter(":edit", editKQML);
	report(msg, ans);
      } catch (Table.BadEdit ex) {
	ex.rethrowAsInvalidArgument(content);
      }
    } else if (verb.equals("auto-split-columns")) {
      KQMLToken tableID = Args.getTypedArgument(content, ":table", KQMLToken.class);
      Table table = HasID.get(tableID.toString(), Table.class);
      List<Table.Edit> edits = table.autoSplitColumns();
      TableEditMenu menu = tableModel2menu.get(table);
      if (menu != null) menu.autoSplitColumnsAction.setEnabled(false);
      KQMLPerformative ans = new KQMLPerformative("answer");
      ans.setParameter(":table", table.toKQML());
      KQMLList editsKQML = new KQMLList();
      for (Table.Edit e : edits) {
	editsKQML.add(e.toKQML());
      }
      ans.setParameter(":edits", editsKQML);
      report(msg, ans);
    } else if (verb.equals("auto-merge-cells")) {
      KQMLToken tableID = Args.getTypedArgument(content, ":table", KQMLToken.class);
      Table table = HasID.get(tableID.toString(), Table.class);
      List<Table.Edit> edits = table.autoMergeCells();
      TableEditMenu menu = tableModel2menu.get(table);
      if (menu != null) menu.autoMergeCellsAction.setEnabled(false);
      KQMLPerformative ans = new KQMLPerformative("answer");
      ans.setParameter(":table", table.toKQML());
      KQMLList editsKQML = new KQMLList();
      for (Table.Edit e : edits) {
	editsKQML.add(e.toKQML());
      }
      ans.setParameter(":edits", editsKQML);
      report(msg, ans);
    } else if (verb.equals("save-table")) {
      // interpret :table argument
      String tableID =
	Args.getTypedArgument(content, ":table", KQMLToken.class).toString();
      Table table = HasID.get(tableID, Table.class);
      // interpret :file argument
      KQMLPerformative filePerf =
        interpretFileArg(content, tableID);
      String filename = Args.getTypedArgument(filePerf, ":name", String.class);
      String format = Args.getTypedArgument(filePerf, ":format", String.class);
      // actually save the table to the file
      table.write(format, new File(filename));
      // reply
      answer(msg, ":file", filePerf);
    } else if (verb.equals("get-history")) {
      String tableID =
        Args.getTypedArgument(content, ":of", KQMLToken.class).toString();
      Table table = HasID.get(tableID, Table.class);
      KQMLPerformative ans = new KQMLPerformative("answer");
      ans.setParameter(":origin", table.origin.toKQML());
      KQMLList editsKQML = new KQMLList();
      for (Table.Edit e : table.undoHistory) {
	editsKQML.add(e.toKQML());
      }
      ans.setParameter(":edits", editsKQML);
      ans.setParameter(":num-rows", ""+table.getRowCount());
      ans.setParameter(":num-columns", ""+table.getColumnCount());
      report(msg, ans);
    } else if (verb.equals("select") || verb.equals("deselect")) {
      boolean isSelect = verb.equals("select");
      KQMLPerformative what = Args.getTypedArgument(content, ":what", KQMLPerformative.class);
      String whatVerb = what.getVerb().toLowerCase();
      if (whatVerb.equals("rectangle")) {
	Region region = Region.fromKQML(what);
	region.setHighlighted(isSelect);
	if (isSelect)
	  region.setNew(false);
	else
	  region.remove();
      } else if (whatVerb.equals("rectangles")) {
	KQMLObject pageKQML =
	  Args.getTypedArgument(what, ":page", KQMLObject.class);
	Page page = Page.fromKQML(pageKQML);
	List<Region> regions = page.getRegions();
	synchronized (regions) {
	  for (Region r : regions)
	    r.setHighlighted(isSelect);
	    // TODO? setNew/remove like in single rectangle case
	}
      } else if (TableSelection.verbOK(whatVerb)) {
	TableSelection sel = TableSelection.fromKQML(what);
	SwingUtilities.invokeLater(new Runnable() {
	  @Override public void run() { selectCells(sel, isSelect); }
	});
      } else {
	throw new InvalidArgument(content, ":what", "rectangle or cells");
      }
    } else if (verb.equals("describe")) {
      // extend WindowManager's describe request to cover HasID objects
      String id =
        Args.getTypedArgument(content, ":what", KQMLToken.class).
	toString().toLowerCase();
      if (HasID.has(id)) {
	HasID o = HasID.get(id, HasID.class);
	answer(msg, ":description", o.toKQML());
      } else {
	return super.receiveRequest(msg, verb, content);
      }
    } else {
      return super.receiveRequest(msg, verb, content);
    }
    return true;
  }

  /** Ask the user to choose a PDF file to display, and display it. Returns the
   * same as showOpenDialog().
   */
  int chooseAndDisplayPDF() throws IOException {
    // now make the file chooser for PDF documents
    JFileChooser fc = new JFileChooser(curDir);
    FileNameExtensionFilter filter = new FileNameExtensionFilter("PDF documents", "pdf");
    fc.setFileFilter(filter);
    // let the user choose a file
    int status = fc.showOpenDialog(null);
    curDir = fc.getCurrentDirectory().toString();
    if (status == JFileChooser.APPROVE_OPTION) // user chose a file
      displayPDF(fc.getSelectedFile(), 0, new WindowConfig());
    return status;
  }

  /** Return a fully specified KQML file structure, given an operation with an
   * arbitrary :file argument. May use a file chooser dialog. If tableID is
   * given, table formats are used, and the tableID is used to construct the
   * default file name. Otherwise, PDF format is assumed, and no default
   * filename is used.
   */
  KQMLPerformative interpretFileArg(KQMLPerformative operation, String tableID)
      throws CWCException, KQMLException {
    KQMLObject fileKQML = operation.getParameter(":file");
    KQMLPerformative filePerf = null;
    if (fileKQML == null) {
      // no file given
      if (tableID == null) {
	throw new MissingArgument(operation.getVerb(), ":file");
      } else {
	// use the tableID + ".csv"
	filePerf = new KQMLPerformative("file");
	filePerf.setParameter(":name", new KQMLString(tableID + ".csv"));
	filePerf.setParameter(":format", new KQMLString("text/csv"));
      }
    } else if (fileKQML instanceof KQMLString) {
      // just a string, make a (file...) performative around it
      filePerf = new KQMLPerformative("file");
      String format = "text/csv";
      if (tableID == null) {
	format = "application/pdf";
      } else if (fileKQML.stringValue().endsWith(".html")) {
	format = "text/html";
      } // else assume CSV
      filePerf.setParameter(":name", fileKQML);
      filePerf.setParameter(":format", new KQMLString(format));
    } else if (fileKQML instanceof KQMLList) {
      filePerf = new KQMLPerformative((KQMLList)fileKQML);
      // TODO? allow things like (file :name choose :format "text/csv"), to let the user choose the filename but restrict them to one format
      String filename = Args.getTypedArgument(filePerf, ":name", String.class);
      String format =
	Args.getTypedArgument(filePerf, ":format", String.class, null);
      if (format == null) {
	if (tableID == null) {
	  format = "application/pdf";
	} else if (filename.endsWith(".html")) {
	  format = "text/html";
	} else {
	  format = "text/csv";
	}
      } else if (tableID != null &&
                 !(format.equals("text/csv") || format.equals("text/html"))) {
	throw new InvalidArgument(filePerf, ":format", "\"text/csv\", \"text/html\", or nothing");
      }
      filePerf.setParameter(":format", new KQMLString(format));
    } else if ((fileKQML instanceof KQMLToken) &&
	       fileKQML.toString().equalsIgnoreCase("choose")) {
      // set up the file chooser of the appropriate file type and let the user
      // choose a file
      JFileChooser fc = new JFileChooser(curDir);
      int status;
      if (tableID == null) {
	FileNameExtensionFilter filter =
	  new FileNameExtensionFilter("PDF documents", "pdf");
	fc.setFileFilter(filter);
	status = fc.showOpenDialog(null);
      } else {
	FileNameExtensionFilter csvFilter =
	  new FileNameExtensionFilter("CSV tables", "csv");
	FileNameExtensionFilter htmlFilter =
	  new FileNameExtensionFilter("HTML tables", "html");
	fc.addChoosableFileFilter(csvFilter);
	fc.addChoosableFileFilter(htmlFilter);
	fc.setFileFilter(csvFilter);
	fc.setSelectedFile(new File(tableID + ".csv"));
	status = fc.showSaveDialog(null);
      }
      curDir = fc.getCurrentDirectory().toString();
      if (status != JFileChooser.APPROVE_OPTION) // user didn't choose a file
	// FIXME? maybe this should be a "rejected" instead of a "failure"?
	throw new CWCException("cannot-perform", new KQMLPerformative("cancelled"));
      String selected = fc.getSelectedFile().toString();
      // recurse on the chosen file string
      KQMLPerformative newOp = new KQMLPerformative(operation.getVerb());
      newOp.setParameter(":file", new KQMLString(selected));
      filePerf = interpretFileArg(newOp, tableID);
    } else {
      throw new InvalidArgument(operation, ":file", "string, performative, or choose");
    }
    return filePerf;
  }

  /** Read a PDF document from the file and display the indexed page in a new
   * window. If we're already displaying this file, just set the page and
   * window config.
   */
  void displayPDF(File file, int page, WindowConfig wc) throws IOException {
    file = file.getCanonicalFile();
    if (pdfFile2pane.containsKey(file)) {
      // old file/pane/window; just set page and window config
      PDFPane pane = pdfFile2pane.get(file);
      if (pane.isDisplayable()) { // window still open
	pane.setPage(page);
	JFrame window = (JFrame)pane.getTopLevelAncestor();
	((SwingWindowManager)windowManager).configureWindow(window, wc);
	return;
      } // else window was closed, fall through to make new window again
    }
    // new file/pane/window
    if (wc.title == null) {
      wc.title = file.getPath() + " - " + name;
    }
    PDFPane pane = new PDFPane(file, page);
    pdfFile2pane.put(file, pane);
    pane.addPDFPaneListener(this);
    DocumentMenu menu = new DocumentMenu(this, pane);
    //JFrame window = new JFrame();
    Map.Entry<KQMLToken,JFrame> entry =
      ((SwingWindowManager)windowManager).createWindow(wc);
    JFrame window = entry.getValue();
    // call pageDisplayed() in a WindowListener so it comes after "opened"
    window.addWindowListener(new WindowAdapter() {
      @Override public void windowOpened(WindowEvent evt) {
	pane.emitPageDisplayed();
	evt.getWindow().removeWindowListener(this);
      }
    });
    window.setBackground(Color.WHITE);
    window.setLayout(new BorderLayout());
    window.add(menu, BorderLayout.NORTH);
    window.add(pane, BorderLayout.CENTER);
    window.pack();
    pane.requestFocusInWindow(); // so pgup/dn works
    window.setVisible(true);
  }

  /** Display a previously-parsed table in a new window. */
  void displayTable(Table model, WindowConfig wc) {
    if (wc.title == null) {
      wc.title = model.getID() + " - " + name;
    }
    SpanTable view = new SpanTable(this, model);
    TableSelectionModel selModel = view.getSelectionModel();
    selModel.addListener(this);
    JScrollPane scroll =
      new JScrollPane(view,
                      ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
                      ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS);
    Map.Entry<KQMLToken,JFrame> entry =
     ((SwingWindowManager)windowManager).createWindow(wc);
    JFrame window = entry.getValue();
    // call tableDisplayed() in a WindowListener so it comes after "opened"
    window.addWindowListener(new WindowAdapter() {
      @Override public void windowOpened(WindowEvent evt) {
	tableDisplayed(model, window);
	evt.getWindow().removeWindowListener(this);
      }
    });
    window.setLayout(new BorderLayout());
    TableEditMenu menu = new TableEditMenu(this, model, selModel);
    tableModel2menu.put(model, menu);
    window.add(menu, BorderLayout.NORTH);
    window.add(scroll, BorderLayout.CENTER);
    window.pack();
    window.setVisible(true);
    tableModel2view.put(model, view);
    // HACK: fire tableChanged just to menus, so they can make MergeTables
    // buttons with this newly displayed table
    for (TableEditMenu m : tableModel2menu.values()) { m.tableChanged(null); }
  }

  /** Return a list of tables that are currently displayed. */
  public List<Table> getDisplayedTables() {
    List<Table> ret = new LinkedList<Table>();
    for (Map.Entry<Table, SpanTable> entry : tableModel2view.entrySet()) {
      if (entry.getValue().isShowing())
	ret.add(entry.getKey());
    }
    return ret;
  }

  /** Parse a table in the given region and reply to msg with the data. */
  void parseTable(KQMLPerformative msg, Region region) {
    Table table = new Table(region);
    KQMLObject tableKQML = table.toKQML();
    answer(msg, ":table", tableKQML);
  }

  void selectCells(TableSelection sel, boolean isSelect) {
    Table model = sel.table;
    SpanTable view = tableModel2view.get(model);
    TableSelectionModel selModel = view.getSelectionModel();
    selModel.setSelection(sel, isSelect);
    cellsSelected(sel, isSelect);
  }

  /** Report that a region was (de)selected. */
  void regionChanged(Region region, String verb) {
    KQMLPerformative action = new KQMLPerformative(verb);
    action.setParameter(":what", region.toKQML());
    report(action);
  }

  /** Report that some cells of a table were (de)selected. Negative row/col
   * indices count backwards from the end, so e.g. column -1 is the last column
   * in the table.
   */
  void cellsSelected(TableSelection sel, boolean isSelect) {
    KQMLPerformative action = new KQMLPerformative(isSelect ? "selected" : "deselected");
    KQMLObject what = sel.toKQML();
    action.setParameter(":what", what);
    report(action);
  }

  //// PDFPane.Listener ////

  @Override public void pageDisplayed(Page page, JFrame window) {
    page.addPageListener(this);
    KQMLPerformative action = new KQMLPerformative("displayed");
    action.setParameter(":what", page.toKQML());
    action.setParameter(":where", ((SwingWindowManager)windowManager).getID(window));
    report(action);
  }
  
  @Override public void pageClicked(int x, int y, Page page) {
    KQMLPerformative action = new KQMLPerformative("clicked");
    action.setParameter(":x", ""+x);
    action.setParameter(":y", ""+y);
    action.setParameter(":page", page.getID());
    List<Region> regions = page.getRegionsAt(x, y);
    KQMLList regionsKQML = new KQMLList();
    for (Region r : regions) {
      regionsKQML.add(r.toKQML(false));
    }
    action.setParameter(":regions", regionsKQML);
    report(action);
  }

  public void tableDisplayed(Table table, JFrame window) {
    KQMLPerformative action = new KQMLPerformative("displayed");
    action.setParameter(":what", table.getID()); // almost certainly we just did a parse-table before this, and that already has the description of the table, so just use the ID here
    action.setParameter(":where", ((SwingWindowManager)windowManager).getID(window));
    report(action);
  }

  /** Send a KQML report that the edit was done, redone, or undone. */
  public void reportEdit(Table.Edit edit, boolean isUndo) {
    KQMLPerformative edited = new KQMLPerformative("edited-table");
    edited.setParameter(":table", edit.getTable().getID());
    KQMLObject editKQML = edit.toKQML();
    if (isUndo)
      editKQML = new KQMLList(new KQMLToken("undo"), editKQML);
    edited.setParameter(":edit", editKQML);
    report(edited);
  }

  public void tableSaved(Table table, File file, String format) {
    KQMLPerformative action = new KQMLPerformative("saved");
    action.setParameter(":what", table.getID());
    KQMLPerformative fileKQML = new KQMLPerformative("file");
    fileKQML.setParameter(":name", new KQMLString(file.toString()));
    fileKQML.setParameter(":format", new KQMLString(format));
    action.setParameter(":where", fileKQML);
    report(action);
  }

  //// Page.Listener ////

  @Override public void pageChanged(Page.Event evt) {
    String verb;
    switch (evt.type) {
      case REGION_HIGHLIGHTED: // fall through
      case REGION_ADDED: // fall through
      case REGION_CHANGED:
        return;
      case REGION_STOPPED_CHANGING:
        verb = (evt.getRegion().isNew() ? "selected" : "changed");
	break;
      case REGION_UNHIGHLIGHTED: // fall through
      case REGION_REMOVED:
        if (evt.getRegion().isNew()) return;
        verb = "deselected";
	break;
      default:
        throw new RuntimeException("WTF");
    }
    regionChanged(evt.getRegion(), verb);
  }

  //// TableSelectionListener ////

  @Override public void valueChanged(TableSelection sel) {}

  @Override public void valueStoppedChanging(TableSelection sel) {
    // TODO report deselect? SpanTable doesn't do deselecting yet
    cellsSelected(sel, true);
  }

  public static void main(String[] argv) {
    // this is supposed to help PDFBox render faster on Java 8
    System.setProperty("sun.java2d.cmm", "sun.java2d.cmm.kcms.KcmsServiceProvider");
    // this is supposed to help PDFBox>=2.0.4 render faster
    //System.setProperty("org.apache.pdfbox.rendering.UsePureJavaCMYKConversion", "true");
    // but it seems to have the opposite effect!
    new PDFExtractor(argv).run();
  }
}

