package TRIPS.PDFExtractor;

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.*;
import javax.swing.*;
import TRIPS.KQML.*;
import TRIPS.util.cwc.*;

/** Quick and dirty GUI for driving PDFExtractor and PDFLearn without language.
 */
public class LearningGUI extends StandardCWCModule implements ActionListener {
  JFrame window;
  JList<Rule> learnedRules;
  DefaultListModel<Rule> learnedRulesModel;
  JButton openButton;
  JButton learnButton;
  JTextField assocWithField;
  JButton executeButton;
  JLabel status;

  boolean havePDFLearn;
  KQMLToken prevPageID;
  KQMLToken prevRectangleID;
  KQMLToken prevTableID;
  KQMLPerformative prevTableFile;

  public LearningGUI(String[] argv) {
    super(argv);
  }

  @Override
  public void init() {
    name = "LearningGUI";
    super.init();
    windowManager = new SwingWindowManager(this);
    ready();
    SwingUtilities.invokeLater(new Runnable() {
      @Override public void run() {
	WindowConfig wc = new WindowConfig("PDF Extraction Learning", null, null, 600, 300);
	window = (JFrame)windowManager.createWindow(wc).getValue();
	window.setLayout(new GridBagLayout());
	GridBagConstraints c = new GridBagConstraints();
	openButton = new JButton("Open");
	openButton.addActionListener(LearningGUI.this);
	c.gridx = 0; c.gridy = 0; c.gridwidth = 1; c.gridheight = 1;
	c.fill = GridBagConstraints.BOTH;
	window.add(openButton, c);
	learnButton = new JButton("Learn");
	learnButton.addActionListener(LearningGUI.this);
	c.gridx = 1;
	window.add(learnButton, c);
	assocWithField = new JTextField();
	c.gridx = 2; c.weightx = 1;
	window.add(assocWithField, c);
	executeButton = new JButton("Execute");
	executeButton.addActionListener(LearningGUI.this);
	c.gridx = 3; c.weightx = 0;
	window.add(executeButton, c);
	learnedRulesModel = new DefaultListModel<Rule>();
	learnedRules = new JList<Rule>(learnedRulesModel);
	JScrollPane lrScroll = new JScrollPane(learnedRules);
	c.gridx = 0; c.gridy = 1; c.gridwidth = 4;
	c.weightx = 1; c.weighty = 1;
	window.add(lrScroll, c);
	status = new JLabel("started");
	c.gridy = 2; c.weighty = 0;
	window.add(status, c);
	window.setVisible(true);
	if (havePDFLearn)
	  loadRules();
      }
    });
  }

  void loadRules() {
    // skip if we can't yet
    if (!(havePDFLearn && learnedRulesModel != null))
      return;
    setStatus("loading rules...");
    try {
      ArrayList<Rule> receivedRules = new ArrayList<Rule>();
      AnswerReceiver receiveRules =
	new AnswerReceiver() {
	  @Override public void receiveAnswer(KQMLPerformative ans) throws Exception {
	    KQMLObject rulesKQML = ans.getParameter(":rules");
	    KQMLList rules;
	    if (rulesKQML == null) {
	      throw new MissingArgument("answer", ":rules");
	    } else if (rulesKQML instanceof KQMLList) {
	      rules = (KQMLList)rulesKQML;
	    } else if (rulesKQML.toString().equalsIgnoreCase("NIL")) {
	      rules = new KQMLList();
	    } else {
	      throw new InvalidArgument(ans, ":rules", "list of rule summaries");
	    }
	    // make a Rule for each find-rule, and save edit-rules in findToEdit
	    receivedRules.ensureCapacity(rules.size());
	    Map<String,String> findToEdit = new HashMap<String,String>();
	    int i = 0;
	    for (KQMLObject ruleKQML : rules) {
	      if (!(ruleKQML instanceof KQMLList))
		throw new InvalidArgument(rules, i, "rule summary structure");
	      KQMLPerformative rulePerf =
	        new KQMLPerformative((KQMLList)ruleKQML);
	      String verb = rulePerf.getVerb().toLowerCase();
	      KQMLToken id =
		Args.getTypedArgument(rulePerf, ":id", KQMLToken.class);
	      if (verb.equals("find-rule")) {
		Rule r = new Rule();
		r.findRuleID = id;
		r.assocWith =
		  Args.getTypedArgument(rulePerf, ":assoc-with",
					String.class, null);
		receivedRules.add(r);
	      } else if (verb.equals("edit-rule")) {
		KQMLToken ofw =
		  Args.getTypedArgument(rulePerf, ":origin-found-with",
					KQMLToken.class);
		findToEdit.put(ofw.toString(), id.toString());
	      } else {
		throw new InvalidArgument(rules, i, "find-rule or edit-rule");
	      }
	      i++;
	    }
	    // go through the Rules again and add edit-rule IDs from findToEdit
	    for (Rule r : receivedRules) {
	      if (findToEdit.containsKey(r.findRuleID.toString())) {
		r.parseTable = true;
		r.editRuleID =
		  new KQMLToken(findToEdit.get(r.findRuleID.toString()));
	      }
	    }
	    setStatus("loaded " + receivedRules.size() + " rules");
	  }
	};
      Promise showRules =
        new Promise() {
	  @Override public void run() {
	    SwingUtilities.invokeLater(new Runnable() {
	      @Override public void run() { // Runnable-ception
		learnedRulesModel.clear();
	        for (Rule r : receivedRules) {
		  learnedRulesModel.addElement(r);
		}
		resolve();
	      }
	    });
	  }
	};
      RequestSender doListRules =
	new RequestSender(
	  KQMLPerformative.fromString(
	    "(request :receiver pdflearn :content (list-rules))"),
	  receiveRules
	);
      doListRules.then(showRules);
      doListRules.run();
    } catch (Exception ex) { handleException(ex); }
  }

  @Override public void restart() {
    // avoid closing the window on restart
    // also, we don't define any services, so the rest of super.restart() is
    // unneeded
    loadRules();
  }

  @Override
  public void subscribe() throws IOException {
    super.subscribe();
    String[] reportVerbs = {"displayed", "selected", "saved"};
    for (String v : reportVerbs) {
      send(KQMLPerformative.fromString(
	"(subscribe :content (tell &key :sender pdfextractor :content (report &key :content (" + v + " . *))))"));
    }
    send(KQMLPerformative.fromString(
      "(subscribe :content (tell &key :content (component-status &key :who pdflearn :what ready)))"));
  }

  @Override
  public boolean receiveTell(KQMLPerformative msg, String verb, KQMLPerformative content) throws CWCException, IOException {
    if (verb.equals("report")) {
      KQMLPerformative rc = Args.getTypedArgument(content, ":content", KQMLPerformative.class);
      String rv = rc.getVerb();
      if (rv.equals("displayed")) {
	displayed(rc);
      } else if (rv.equals("selected")) {
	selected(rc);
      } else if (rv.equals("saved")) {
	saved(rc);
      } else {
	throw new UnknownAction(rv);
      }
    } else if (verb.equals("component-status")) { // PDFLearn is ready
      havePDFLearn = true;
      loadRules();
    } else {
      return super.receiveTell(msg, verb, content);
    }
    return true;
  }

  void setStatus(String s) {
    status.setText(s);
    System.err.println(s);
  }

  void handleException(Exception ex) {
    status.setText(ex.toString());
    ex.printStackTrace();
  }

  void displayed(KQMLPerformative action) throws CWCException, IOException {
    KQMLObject what = action.getParameter(":what");
    if (what == null) {
      throw new MissingArgument(action.getVerb(), ":what");
    } else if (what instanceof KQMLToken) { // table ID
      prevTableID = (KQMLToken)what;
      setStatus("displayed " + prevTableID.toString());
    } else if (what instanceof KQMLList) { // page description
      KQMLPerformative desc = new KQMLPerformative((KQMLList)what);
      prevPageID = Args.getTypedArgument(desc, ":id", KQMLToken.class);
      setStatus("displayed " + prevPageID.toString());
    } else {
      throw new InvalidArgument(action, ":what", "table ID or page description");
    }
  }

  void selected(KQMLPerformative action) throws CWCException, IOException {
    KQMLPerformative what =
      Args.getTypedArgument(action, ":what", KQMLPerformative.class);
    String verb = what.getVerb().toLowerCase();
    if (verb.equals("rectangle")) {
      prevRectangleID = Args.getTypedArgument(what, ":id", KQMLToken.class);
      prevTableID = null;
      prevTableFile = null;
      // FIXME what if this is a column-splitting rectangle?
      setStatus("selected " + prevRectangleID.toString());
    } // else it's a table part, don't care
  }

  void saved(KQMLPerformative action) throws CWCException, IOException {
    prevTableID = Args.getTypedArgument(action, ":what", KQMLToken.class);
    prevTableFile =
      Args.getTypedArgument(action, ":where", KQMLPerformative.class);
    setStatus("saved " + prevTableID.toString() + " to " + prevTableFile.getParameter(":name"));
    // if we saved the table as not-HTML, forget it (PDFLearn needs HTML)
    if (!prevTableFile.getParameter(":format").stringValue().
         equals("text/html"))
      prevTableFile = null;
  }

  @Override
  public void actionPerformed(ActionEvent evt) {
    try {
      String cmd = evt.getActionCommand();
      if (cmd.equals("Open")) {
	open();
      } else if (cmd.equals("Learn")) {
	learn();
      } else if (cmd.equals("Execute")) {
	execute();
      } else {
	throw new UnknownAction(cmd);
      }
    } catch (Exception ex) { handleException(ex); }
  }

  /** Poor-man's JS promise. No value passing, no error handling. */
  abstract class Promise implements Runnable {
    Promise next;
    public Promise then(Promise next) {
      this.next = next;
      return next;
    }
    public void resolve() {
      if (next != null)
	next.run();
    }
  }

  /** Receive a reply answer, process it, and resolve a promise. */
  abstract class AnswerReceiver extends Promise implements KQMLContinuation {
    abstract public void receiveAnswer(KQMLPerformative ans) throws Exception;
    @Override public void receive(KQMLPerformative msg) {
      try {
	KQMLPerformative report =
	  Args.getTypedArgument(msg, ":content", KQMLPerformative.class);
	if (!report.getVerb().equalsIgnoreCase("report"))
	  throw new UnknownAction(report.getVerb());
	KQMLPerformative answer =
	  Args.getTypedArgument(report, ":content", KQMLPerformative.class);
	String answerVerb = answer.getVerb().toLowerCase();
	if (answerVerb.equals("answer")) {
	  receiveAnswer(answer);
	  resolve();
	} else {
	  throw new RuntimeException(answer.toString());
	}
      } catch (Exception ex) { handleException(ex); }
    }
    @Override public void run() { throw new UnsupportedOperationException(); }
  }

  /** Send a request and process the reply with an AnswerReceiver, then resolve
   * the promise when the AnswerReceiver does.
   */
  class RequestSender extends Promise {
    KQMLPerformative request;
    AnswerReceiver receiver;
    public RequestSender(KQMLPerformative request, AnswerReceiver receiver) {
      this.request = request;
      this.receiver = receiver;
    }
    @Override public Promise then(Promise next) { return receiver.then(next); }
    @Override public void run() {
      try {
	sendWithContinuation(request, receiver);
      } catch (Exception ex) { handleException(ex); }
    }
  }

  void open() throws IOException {
    send(KQMLPerformative.fromString(
      "(request :receiver PDFExtractor :content (display :file choose))"));
  }

  void learn() {
    setStatus("learning...");
    Rule r = new Rule();
    r.assocWith = assocWithField.getText();
    assocWithField.setText("");
    if (r.assocWith.isEmpty())
      r.assocWith = null;
    Promise start = null;
    Promise finish = new Promise() {
      @Override public void run() {
	try {
	  learnedRulesModel.addElement(r);
	  setStatus("learned to " + r.toString());
	} catch (Exception ex) { handleException(ex); }
      }
    };
    KQMLPerformative learnToFind = new KQMLPerformative("learn-to-find");
    learnToFind.setParameter(":target-region", prevRectangleID);
    if (r.assocWith != null)
      learnToFind.setParameter(":assoc-with", new KQMLString(r.assocWith));
    KQMLPerformative learnToFindReq = new KQMLPerformative("request");
    learnToFindReq.setParameter(":content", learnToFind);
    AnswerReceiver receiveFindRuleID = 
      new AnswerReceiver() {
	@Override
	public void receiveAnswer(KQMLPerformative ans) throws Exception {
	  r.findRuleID = Args.getTypedArgument(ans, ":rule", KQMLToken.class);
	  setStatus("learned find-rule " + r.findRuleID.toString());
	}
      };
    RequestSender doLearnToFind =
      new RequestSender(learnToFindReq, receiveFindRuleID);
    start = doLearnToFind;
    if (prevTableID == null) { // no table, just a region
      doLearnToFind.then(finish);
    } else { // learning to find a table
      r.parseTable = true;
      // make sure we have the table file to pass to learn-to-find
      if (prevTableFile == null) {
	KQMLPerformative saveTable = new KQMLPerformative("save-table");
	saveTable.setParameter(":table", prevTableID);
	saveTable.setParameter(":file", new KQMLString(prevTableID + ".html"));
	KQMLPerformative saveTableReq = new KQMLPerformative("request");
	saveTableReq.setParameter(":content", saveTable);
	AnswerReceiver receiveTableFile = new AnswerReceiver() {
	  @Override
	  public void receiveAnswer(KQMLPerformative ans) throws Exception {
	    prevTableFile =
	      Args.getTypedArgument(ans, ":file", KQMLPerformative.class);
	    learnToFind.setParameter(":table-file", prevTableFile);
	    setStatus("saved " + prevTableID.toString() + " to " + prevTableFile.getParameter(":name"));
	  }
	};
	RequestSender doSaveTable =
	  new RequestSender(saveTableReq, receiveTableFile);
	doSaveTable.then(doLearnToFind);
	start = doSaveTable;
      } else {
	learnToFind.setParameter(":table-file", prevTableFile);
      }
      // also learn to edit the table
      // TODO? do a get-history request to find out if the table was edited at
      // all, and if not, skip learn-to-edit and leave editRuleID null
      KQMLPerformative learnToEdit = new KQMLPerformative("learn-to-edit");
      learnToEdit.setParameter(":table", prevTableID);
      KQMLPerformative learnToEditReq = new KQMLPerformative("request");
      learnToEditReq.setParameter(":content", learnToEdit);
      AnswerReceiver receiveEditRuleID =
	new AnswerReceiver() {
	  @Override
	  public void receiveAnswer(KQMLPerformative ans) throws Exception {
	    r.editRuleID = Args.getTypedArgument(ans, ":rule", KQMLToken.class);
	    setStatus("learned edit-rule " + r.editRuleID.toString());
	  }
	};
      RequestSender doLearnToEdit =
        new RequestSender(learnToEditReq, receiveEditRuleID);
      doLearnToFind.then(doLearnToEdit).then(finish);
    }
    start.run();
  }

  void execute() throws CWCException {
    setStatus("executing...");
    Rule r = learnedRules.getSelectedValue();
    if (r == null)
      throw new MissingArgument("execute", "rule");
    if (prevPageID == null)
      throw new MissingArgument("execute", "page");
    setStatus("trying to " + r.toString() + " on " + prevPageID.toString());
    KQMLPerformative find = new KQMLPerformative("find");
    find.setParameter(":rule", r.findRuleID);
    find.setParameter(":page", prevPageID);
    KQMLPerformative findReq = new KQMLPerformative("request");
    findReq.setParameter(":content", find);
    final KQMLToken[] regionID = {null};
    AnswerReceiver receiveRegionID = new AnswerReceiver() {
      @Override
      public void receiveAnswer(KQMLPerformative ans) throws Exception {
	regionID[0] =
	  Args.getTypedArgument(ans, ":target-region", KQMLToken.class);
	setStatus("found " + regionID[0].toString());
      }
    };
    RequestSender doFind = new RequestSender(findReq, receiveRegionID);
    if (r.parseTable) {
      // parse table
      KQMLPerformative parseTableReq = new KQMLPerformative("request");
      Promise makeParseTable = new Promise() {
	@Override public void run() {
	  KQMLPerformative parseTable = new KQMLPerformative("parse-table");
	  parseTable.setParameter(":region", regionID[0]);
	  parseTableReq.setParameter(":content", parseTable);
	  resolve();
	}
      };
      KQMLToken[] tableID = {null};
      AnswerReceiver receiveTable = new AnswerReceiver() {
	@Override
	public void receiveAnswer(KQMLPerformative ans) throws Exception {
	  KQMLPerformative table =
	    Args.getTypedArgument(ans, ":table", KQMLPerformative.class);
	  tableID[0] = Args.getTypedArgument(table, ":id", KQMLToken.class);
	  setStatus("parsed " + tableID[0].toString());
	}
      };
      RequestSender doParseTable =
        new RequestSender(parseTableReq, receiveTable);
      // display/edit table
      // (no need to use RequestSender/AnswerReceiver here, since we don't need
      // to wait for the replies)
      Promise doDisplayAndEditTable = new Promise() {
	@Override public void run() {
	  KQMLPerformative displayTableReq = new KQMLPerformative("request");
	  KQMLPerformative displayTable = new KQMLPerformative("display-table");
	  displayTable.setParameter(":table", tableID[0]);
	  displayTableReq.setParameter(":content", displayTable);
	  setStatus("displaying " + tableID[0].toString());
	  send(displayTableReq);
	  KQMLPerformative editReq = new KQMLPerformative("request");
	  KQMLPerformative edit = new KQMLPerformative("edit");
	  edit.setParameter(":rule", r.editRuleID);
	  edit.setParameter(":table", tableID[0]);
	  editReq.setParameter(":content", edit);
	  setStatus("editing " + tableID[0].toString() + " with " + r.editRuleID.toString());
	  send(editReq);
	}
      };
      // put it all together
      doFind.
	then(makeParseTable).
	then(doParseTable).
	then(doDisplayAndEditTable);
    }
    doFind.run();
  }

  class Rule {
    KQMLToken findRuleID;
    String assocWith; // null if find rule has no :assoc-with
    boolean parseTable;
    KQMLToken editRuleID; // null if parseTable=false (or maybe if parseTable=true and the table wasn't edited, but I haven't implemented that yet)
    public Rule() {
      this.findRuleID = null;
      this.assocWith = null;
      this.parseTable = false;
      this.editRuleID = null;
    }

    public String toString() {
      StringBuilder b = new StringBuilder();
      b.append("find a ");
      b.append(parseTable ? "table" : "region");
      if (assocWith != null) {
	b.append(" associated with \"");
	b.append(assocWith);
	b.append("\"");
      }
      if (editRuleID != null)
	b.append(", and edit it.");
      b.append(" (");
      b.append(findRuleID.toString());
      if (editRuleID != null) {
	b.append(", ");
	b.append(editRuleID.toString());
      }
      b.append(")");
      return b.toString();
    }
  }

  public static void main(String[] args) {
    new LearningGUI(args).run();
  }
}
