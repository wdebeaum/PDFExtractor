package TRIPS.PDFExtractor;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.geom.Rectangle2D;
import java.util.EnumSet;
import java.util.List;
import javax.swing.InputVerifier;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.text.JTextComponent;
import technology.tabula.RectangularTextContainer;
import technology.tabula.TextElement;
import TRIPS.KQML.KQMLObject;
import TRIPS.KQML.KQMLPerformative;
import TRIPS.KQML.KQMLString;
import TRIPS.KQML.KQMLToken;
import TRIPS.util.cwc.Args;
import TRIPS.util.cwc.CWCException;
import TRIPS.util.cwc.InvalidArgument;

public class CellProperties implements Cloneable {
  /** Text content of this cell to replace original.getText(). May be null. */
  String newText;
  /** Annotations applied to this cell. */
  String annotations;
  public static enum Type {
    DATA, ROW_HEADING, COLUMN_HEADING;
    public String toString() {
      return name().toLowerCase().replace('_', ' ');
    }
    public KQMLObject toKQML() {
      return new KQMLToken(name().toLowerCase().replace('_', '-'));
    }
    public static Type fromKQML(KQMLToken k) {
      return Enum.valueOf(Type.class,
			  k.toString().toUpperCase().replace('-', '_'));
    }
  };
  /** Whether this cell is just data, or a heading for other cells. */
  Type type;
  /** Selection of cells that this cell is acting as a heading for. Will be
   * null if type is DATA.
   */
  TableSelection headingFor;

  public CellProperties(String newText, String annotations, Type type, TableSelection headingFor) {
    this.newText = newText;
    this.annotations = annotations;
    this.type = type;
    this.headingFor = headingFor;
  }

  public CellProperties() {
    this(null, "", Type.DATA, null);
  }

  public KQMLPerformative toKQML(KQMLPerformative perf, boolean isNote) {
    if (newText != null)
      perf.setParameter(":content", new KQMLString(newText));
    perf.setParameter(":annotations", new KQMLString(annotations));
    if (!isNote) {
      perf.setParameter(":type", type.toKQML());
      if (headingFor != null)
	perf.setParameter(":heading-for",
			  new KQMLString(headingFor.toString()));
    }
    return perf;
  }

  public KQMLPerformative toKQML(KQMLPerformative perf) {
    return toKQML(perf, false);
  }

  public KQMLObject toKQML() {
    KQMLPerformative p = new KQMLPerformative("cell-properties");
    return toKQML(p);
  }

  public static CellProperties fromKQML(Table table, KQMLPerformative perf) throws CWCException {
    String newText =
      Args.getTypedArgument(perf, ":content", String.class, null);
    String annotations =
      Args.getTypedArgument(perf, ":annotations", String.class, "");
    KQMLToken typeKQML =
      Args.getTypedArgument(perf, ":type", KQMLToken.class,
			    new KQMLToken("data"));
    Type type = Type.fromKQML(typeKQML);
    String headingForStr =
      Args.getTypedArgument(perf, ":heading-for", String.class, null);
    // TODO? handle :heading-for NIL
    TableSelection headingFor = null;
    if (headingForStr != null && !headingForStr.equalsIgnoreCase("N/A")) {
      try {
        headingFor = TableSelection.fromString(table, headingForStr);
      } catch (NumberFormatException ex) {
	throw new InvalidArgument(perf, ":heading-for",
				  ex.getMessage().
				  replace("^expected ", "").
				  replace(", but got .*$", ""));
      }
    }
    CellProperties props =
      new CellProperties(newText, annotations, type, headingFor);
    // if this is a heading but we don't have headingFor (and this isn't an
    // edit-cells edit, which won't have :row/:column), set it to the default
    if (headingFor == null && props.isHeading() &&
	!perf.getVerb().equalsIgnoreCase("edit-cells")) {
      int row = Args.getTypedArgument(perf, ":row", Integer.class);
      int col = Args.getTypedArgument(perf, ":column", Integer.class);
      props.headingFor =
        props.getDefaultHeadingFor(table, row, col, table.getSpanAt(row, col));
    }
    return props;
  }

  @Override
  public CellProperties clone() {
    try {
      return (CellProperties)super.clone();
    } catch (CloneNotSupportedException ex) {
      throw new RuntimeException("WTF");
    }
  }

  /** Return a new CellProperties object that merges the given ones, for use as
   * the properties of a MergedCell. Replacement text contents are not merged,
   * they're just always null, so that the text of the MergedCell (which
   * already takes account of the edited text of the individual cells) can show
   * through.
   */
  public static CellProperties merge(List<CellProperties> propses) {
    // if all annotations are either blank or equal to the other non-blank
    // annotations, just use the common annotation once; otherwise use the
    // non-blank annotations joined with newlines
    String commonAnnotations = null;
    for (CellProperties props : propses) {
      if (!props.annotations.equals("")) {
	if (commonAnnotations == null) {
	  commonAnnotations = props.annotations;
	} else if (!props.annotations.equals(commonAnnotations)) {
	  commonAnnotations = null;
	  break;
	}
      }
    }
    String annotations;
    if (commonAnnotations != null) {
      annotations = commonAnnotations;
    } else {
      boolean first = true;
      StringBuilder anns = new StringBuilder();
      for (CellProperties props : propses) {
	if (!props.annotations.equals("")) {
	  if (first) {
	    first = false;
	  } else {
	    anns.append('\n');
	  }
	  anns.append(props.annotations);
	}
      }
      annotations = anns.toString();
    }
    // take the first heading type, if any; DATA otherwise
    Type type = Type.DATA;
    for (CellProperties props : propses) {
      if (props.isHeading()) {
	type = props.type;
	break;
      }
    }
    // take the headingFor selection that minimally contains all the others, or
    // null if there are no others
    TableSelection headingFor = null;
    for (CellProperties props : propses) {
      if (props.headingFor != null) {
	if (headingFor == null) {
	  headingFor = props.headingFor;
	} else {
	  headingFor =
	    new TableSelection(headingFor.table,
	      Integer.min(headingFor.firstRow, props.headingFor.firstRow),
	      Integer.min(headingFor.firstCol, props.headingFor.firstCol),
	      Integer.max(headingFor.lastRow, props.headingFor.lastRow),
	      Integer.max(headingFor.lastCol, props.headingFor.lastCol)
	    );
	}
      }
    }
    CellProperties ret = new CellProperties(null, annotations, type, headingFor);
    return ret;
  }

  /** Get the text that should be in the "heading for" input field, according
   * to the headingFor member variable.
   */
  public String getHeadingForText() {
    return (headingFor == null ? "N/A" : headingFor.toString());
  }

  public boolean isHeading() { return (type != Type.DATA); }

  /** Get the default value of headingFor given this CellProperties and the
   * context.
   */
  public TableSelection getDefaultHeadingFor(Table table, int row, int col, Dimension span) {
    switch (type) {
      case DATA:
        // data cells are not headings for anything
	return null;
      case ROW_HEADING:
	// select the rows this heading is in, after the heading
	return
	  new TableSelection(table,
	    row, col + span.width,
	    row + span.height - 1, -1);
      case COLUMN_HEADING:
	// select the columns this heading is in, after the heading
	return
	  new TableSelection(table,
	    row + span.height, col,
	    -1, col + span.width - 1);
      // java, can't you see that this switch is exhaustive, and all branches
      // return? I don't need a separate return statement after it!
      default:
        throw new RuntimeException("WTF");
    }
  }

  /** Get the text string to use in a (HTML title attribute) tooltip for this
   * cell. Return null if no tooltip should be made.
   */
  public String getToolTipText() {
    boolean haveAnnotations = (annotations.length() > 0);
    boolean isHeading = isHeading();
    if (!(haveAnnotations || isHeading))
      return null;
    StringBuilder b = new StringBuilder();
    if (haveAnnotations)
      b.append(annotations);
    if (haveAnnotations && isHeading)
      b.append("\n\n");
    if (isHeading)
      b.append(type.toString()).append(" for ").append(headingFor.toString());
    return b.toString();
  }

  /** Get the HTML string to use in a (swing) tooltip for this cell. Return
   * null if no tooltip should be made.
   */
  public String getToolTipHTML() {
    String tooltip = getToolTipText();
    if (tooltip == null) return null;
    return new HTMLBuilder().textLines(tooltip).toDocumentString();
  }

  /** Component for editing the properties of a cell. */
  public class Editor extends JPanel implements ActionListener {
    RectangularTextContainer<TextElement> original;
    Table table;
    int row;
    int col;
    Dimension span;
    GridBagLayout layout;
    GridBagConstraints constraints;
    JTextField hfInput;

    /** Make a new full Editor for a single cell. If isNote == true, this is a
     * caption or footnote, so don't show cell type/heading for fields. */
    public Editor(RectangularTextContainer<TextElement> original, boolean isNote) {
      super(new GridBagLayout());
      table = null;
      this.original = original;
      layout = (GridBagLayout)getLayout();
      constraints = new GridBagConstraints();
      constraints.gridwidth = 1;
      constraints.gridheight = 1;
      constraints.gridy = 0;

      Verifier verifier = new Verifier();

      JLabel orig =
        new JLabel(Cell.getHTMLOf(original).toDocumentString());
      addLabeledField("Original content", orig);

      JTextArea newInput =
        new JTextArea(newText == null ? Cell.getTextOf(original) : newText,
		      2, 40);
      newInput.putClientProperty("name", "newText");
      newInput.setInputVerifier(verifier);
      addLabeledField("Edited content", newInput);
      
      JTextArea annInput = new JTextArea(annotations, 2, 40);
      annInput.putClientProperty("name", "annotations");
      annInput.setInputVerifier(verifier);
      addLabeledField("Annotations", annInput);

      if (!isNote) {
	JComboBox<Type> typeInput =
	  new JComboBox<Type>(EnumSet.allOf(Type.class).toArray(new Type[3]));
	typeInput.setSelectedItem(type);
	typeInput.setActionCommand("type");
	typeInput.addActionListener(this);
	addLabeledField("Cell type", typeInput);

	hfInput = new JTextField(headingFor == null ? "N/A" : headingFor.toString(), 10);
	hfInput.putClientProperty("name", "headingFor");
	hfInput.setInputVerifier(verifier);
	addLabeledField("Heading for cells", hfInput);
      }
    }

    /** Make a new partial editor for multiple cells. */
    public Editor() {
      super(new GridBagLayout());
      table = null;
      this.original = null;
      layout = (GridBagLayout)getLayout();
      constraints = new GridBagConstraints();
      constraints.gridwidth = 1;
      constraints.gridheight = 1;
      constraints.gridy = 0;

      Verifier verifier = new Verifier();

      JTextArea annInput = new JTextArea(annotations, 2, 40);
      annInput.putClientProperty("name", "annotations");
      annInput.setInputVerifier(verifier);
      addLabeledField("Annotations", annInput);

      JComboBox<Type> typeInput =
        new JComboBox<Type>(EnumSet.allOf(Type.class).toArray(new Type[3]));
      typeInput.setSelectedItem(type);
      typeInput.setActionCommand("type");
      typeInput.addActionListener(this);
      addLabeledField("Cell type", typeInput);

      hfInput = null;
    }

    void addLabeledField(String labelStr, Component field) {
      constraints.gridx = 0;
      constraints.anchor = GridBagConstraints.NORTHEAST;
      JLabel label = new JLabel(labelStr + ": ");
      layout.setConstraints(label, constraints);
      add(label);
      constraints.gridx = 1;
      constraints.anchor = GridBagConstraints.NORTHWEST;
      layout.setConstraints(field, constraints);
      add(field);
      constraints.gridy++;
      JSeparator sep = new JSeparator();
      constraints.fill = GridBagConstraints.HORIZONTAL;
      constraints.gridx = 0;
      constraints.gridwidth = 2;
      layout.setConstraints(sep, constraints);
      add(sep);
      constraints.fill = GridBagConstraints.NONE;
      constraints.gridwidth = 1;
      constraints.gridy++;
    }

    /** Set additional context for the editor, that is more easily obtained by
     * the Table than the CellProperties. Only required for a single-cell
     * editor.
     */
    public void setContext(Table table, int row, int col, Dimension span) {
      this.table = table;
      this.row = row;
      this.col = col;
      this.span = span;
    }

    public CellProperties getProperties() { return CellProperties.this; }

    //// ActionListener ////

    @Override public void actionPerformed(ActionEvent evt) {
      String cmd = evt.getActionCommand();
      switch (cmd) {
	case "type":
	  Type newType = (Type)(((JComboBox)evt.getSource()).getSelectedItem());
	  if (newType == type) break;
	  type = newType;
	  // type changed, reset headingFor to its default for the type
	  if (hfInput != null) {
	    headingFor = getDefaultHeadingFor(table, row, col, span);
	    hfInput.setText(getHeadingForText());
	  }
	  break;
	default:
	  throw new RuntimeException("WTF");
      }
    }

    // ugh, why is InputVerifier not an interface?!
    class Verifier extends InputVerifier {
      @Override public boolean verify(JComponent component) {
	JTextComponent textComp = (JTextComponent)component;
	String text = textComp.getText();
	String cmd = (String)component.getClientProperty("name");
	switch (cmd) {
	  case "newText":
	    newText = (text.equals(Cell.getTextOf(original)) ? null : text);
	    return true;
	  case "annotations":
	    annotations = text;
	    return true;
	  case "headingFor":
	    if (text.equalsIgnoreCase("N/A")) {
	      headingFor = null;
	      return true;
	    }
	    if (table == null)
	      throw new RuntimeException("Editor.table not set yet!");
	    try {
	      headingFor = TableSelection.fromString(table, text);
	      return true;
	    } catch (NumberFormatException ex) {
	      String prevText = getHeadingForText();
	      System.err.println("invalid cell range \"" + text + "\" entered; reverting to previous value \"" + prevText + "\"");
	      System.err.println("error was: " + ex.toString());
	      ex.printStackTrace();
	      textComp.setText(prevText);
	      return false;
	    }
	  default:
	    throw new RuntimeException("WTF");
	}
      }
    }
  }

  public Editor getEditor(RectangularTextContainer<TextElement> original, boolean isNote) {
    return new Editor(original, isNote);
  }

  public Editor getMulticellEditor() {
    return new Editor();
  }
}
