package TRIPS.PDFExtractor;

import java.awt.event.ActionEvent;
import java.awt.Dimension;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JToolBar;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import TRIPS.KQML.KQMLList;
import TRIPS.KQML.KQMLObject;
import TRIPS.KQML.KQMLPerformative;
import TRIPS.KQML.KQMLToken;
import TRIPS.util.cwc.CWCException;

/** Menu bar associated with a {@link Table} displayed in a {@link SpanTable}.
 */
public class TableEditMenu extends JToolBar implements TableModelListener, Page.Listener {
  PDFExtractor module;
  Table table;
  TableSelectionModel selectionModel;
  SaveAction saveCSV;
  SaveAction saveHTML;
  UndoRedoAction undoAction;
  UndoRedoAction redoAction;
  UndoMergeCellsAction undoMergeCellsAction;
  AutoSplitColumnsAction autoSplitColumnsAction;
  AutoMergeCellsAction autoMergeCellsAction;
  List<JButton> mergeTablesButtons;
  List<JButton> splitColumnButtons;

  public TableEditMenu(PDFExtractor module, Table table, TableSelectionModel selectionModel) {
    super();
    setFloatable(false);
    this.module = module;
    this.table = table;
    this.selectionModel = selectionModel;
    saveCSV = new SaveAction("text/csv");
    add(saveCSV);
    saveHTML = new SaveAction("text/html");
    add(saveHTML);
    undoAction = new UndoRedoAction(false);
    add(undoAction);
    undoAction.setEnabled(false);
    redoAction = new UndoRedoAction(true);
    add(redoAction);
    redoAction.setEnabled(false);
    undoMergeCellsAction = new UndoMergeCellsAction();
    selectionModel.addListener(undoMergeCellsAction);
    add(undoMergeCellsAction);
    undoMergeCellsAction.setEnabled(false);
    if (table.origin == null) {
      autoSplitColumnsAction = null;
      autoMergeCellsAction = null;
    } else {
      autoSplitColumnsAction = new AutoSplitColumnsAction();
      add(autoSplitColumnsAction);
      autoMergeCellsAction = new AutoMergeCellsAction();
      add(autoMergeCellsAction);
    }
    for (Class<? extends Table.Edit> c : Table.getEditClasses()) {
      SelectionEditAction a = new SelectionEditAction(c);
      selectionModel.addListener(a);
      add(a);
      // the selection is empty, so disable the button for now
      a.setEnabled(false);
    }
    mergeTablesButtons = new ArrayList<JButton>();
    updateMergeTablesActions();
    splitColumnButtons = new ArrayList<JButton>();
    updateSplitColumnActions();
    for (Table other : module.getDisplayedTables()) {
      other.addTableModelListener(this);
    }
    table.addTableModelListener(this); // table isn't displayed yet, but will be
    if (table.origin != null)
      table.origin.getPage().addPageListener(this);
    setPreferredSize(new Dimension(44*14+22+2, 46)); // enough room for 14.5 buttons
  }

  // when adding an action that keeps its button, add the button to the action
  public JButton add(ActionWithButton a) {
    JButton b = super.add(a);
    a.setButton(b);
    return b;
  }

  /** Abstract Action that corresponds to a Table.Edit. */
  public abstract class EditAction extends ActionWithButton {
    Table.Edit edit;

    public EditAction(String buttonLabel, String iconName) {
      super(buttonLabel, iconName);
      edit = null;
    }

    //// ActionListener ////

    @Override public void actionPerformed(ActionEvent evt) {
      // save the edit to be done, since doing it may cause us to set edit=null
      Table.Edit savedEdit = edit;
      // if it's an EditCell(s), tell it about the module so it can report the
      // edit when the user closes the editor dialog it creates (also so that
      // it can find the window it's in, so it can own the editor dialog)
      if (edit instanceof Table.EditCell)
	((Table.EditCell)edit).module = module;
      if (edit instanceof Table.EditCells)
	((Table.EditCells)edit).module = module;
      try {
	table.edit(edit);
      } catch (CWCException ex) {
	throw new RuntimeException("edit failed unexpectedly", ex);
      } catch (Table.BadEdit ex) {
	throw new RuntimeException("edit failed unexpectedly", ex);
      }
      // if it's not an EditCell(s), we report it ourselves
      if (!((savedEdit instanceof Table.EditCell) ||
	    (savedEdit instanceof Table.EditCells)))
	module.reportEdit(savedEdit, false);
    }
  }

  /** Generic EditAction for Table.Edits whose argument can be a TableSelection.
   */
  public class SelectionEditAction extends EditAction implements TableSelectionListener {
    Class<? extends Table.Edit> editClass;
    Method editFromSelection;

    public SelectionEditAction(Class<? extends Table.Edit> c) {
      super(Table.getButtonLabel(c), Table.getKQMLVerb(c));
      editClass = c;
      String className = editClass.getSimpleName();
      String fromSelectionName =
	className.substring(0,1).toLowerCase() +
	className.substring(1) +
	"FromSelection";
      try {
	editFromSelection =
	  Table.class.getMethod(fromSelectionName, TableSelection.class);
      } catch (ReflectiveOperationException ex) {
	throw new RuntimeException("improperly defined Edit subclass", ex);
      }
    }

    //// TableSelectionListener ////

    @Override public void valueChanged(TableSelection sel) {
      try {
	edit = (Table.Edit)editFromSelection.invoke(table, sel);
      } catch (InvocationTargetException ex) { // wraps BadEdit
	edit = null;
      } catch (ReflectiveOperationException ex) {
	edit = null;
	throw new RuntimeException("improperly defined Edit subclass", ex);
      } finally {
	setEnabled(edit != null);
      }
    }
    @Override public void valueStoppedChanging(TableSelection sel) {}
  }

  //// EditActions with other arguments ////

  public class MergeTablesAction extends EditAction {
    public MergeTablesAction(Table other) throws Table.BadEdit {
      super("Append rows of " + other.getID(), "merge-tables");
      if (table == other)
	throw new Table.BadEdit("expected another table");
      if (table.getColumnCount() != other.getColumnCount())
	throw new Table.BadEdit("expected equal column counts");
      for (Table.Edit e : table.history) {
	if (e instanceof Table.MergeTables) {
	  Table.MergeTables mt = (Table.MergeTables)e;
	  if (mt.others.contains(other)) {
	    throw new Table.BadEdit("we already merged this table");
	  }
	}
      }
      for (Table.Edit e : other.history) {
	if (e instanceof Table.MergeTables) {
	  Table.MergeTables mt = (Table.MergeTables)e;
	  if (mt.others.contains(table)) {
	    throw new Table.BadEdit("this table already merged us");
	  }
	}
      }
      List<Table> others = new ArrayList<Table>(1);
      others.add(other);
      edit = table.new MergeTables(others);
    }
  }

  void updateMergeTablesActions() {
    for (JButton b : mergeTablesButtons) { remove(b); }
    mergeTablesButtons.clear();
    // find other tables that:
    // - are currently displayed
    // - have the same number of columns as ours
    // - are not already merged into ours
    // and make MergeTablesActions for them
    for (Table other : module.getDisplayedTables()) {
      // make sure we're listening to this other table
      List<TableModelListener> oldListeners =
        Arrays.asList(other.getTableModelListeners());
      if (!oldListeners.contains(this))
	other.addTableModelListener(this);
      try {
	MergeTablesAction a = new MergeTablesAction(other);
	mergeTablesButtons.add(add(a));
      } catch (Table.BadEdit ex) {
	// ignore this other table
      }
    }
  }

  // TableModelListener
  @Override public void tableChanged(TableModelEvent evt) {
    undoAction.setEnabled(table.canUndo());
    redoAction.setEnabled(table.canRedo());
    // FIXME?
    // I'm not sure why I thought this was necessary. SpanTable also listens
    // for tableChanged, and clears the selection, which fires valueChanged.
    // Doing it here just means that undoMergeCellsAction sees the old
    // selection on the new table, which can cause indexing errors.
    //undoMergeCellsAction.valueChanged(new TableSelection(selectionModel));//ick.
    updateSplitColumnActions();
    updateMergeTablesActions();
    revalidate();
    repaint();
  }

  public class SplitColumnAction extends EditAction {
    public SplitColumnAction(float newColBoundX) throws Table.BadEdit {
      super("Split column at X=" + newColBoundX, "split-column");
      double minX = table.origin.getMinX();
      double maxX = table.origin.getMaxX();
      if (newColBoundX <= minX || newColBoundX >= maxX) {
	throw new Table.BadEdit("new column boundary is outside the table");
      }
      for (Float cbx : table.colBoundXs) {
	if (newColBoundX == (float)cbx)
	  throw new Table.BadEdit("column boundary not new");
      }
      for (Table.Edit e : table.history) {
	if (e instanceof Table.MergeTables) {
	  throw new Table.BadEdit("split-column conflicts with merge-tables already in edit history");
	}
      }
      edit = table.new SplitColumn(newColBoundX);
    }
  }

  void updateSplitColumnActions() {
    for (JButton b : splitColumnButtons) { remove(b); }
    splitColumnButtons.clear();
    if (table.origin == null)
      return;
    List<Region> regions = table.origin.getPage().getRegions();
    synchronized (regions) {
      for (Region r : regions) {
	if (r.source != Region.Source.USER) continue;
	try {
	  float newColBoundX = (float)r.getMinX();
	  SplitColumnAction a = new SplitColumnAction(newColBoundX);
	  // TODO listen for changes to the region?
	  splitColumnButtons.add(add(a));
	} catch (Table.BadEdit ex) {
	  // ignore this X coordinate
	}
      }
    }
  }

  // Page.Listener
  @Override public void pageChanged(Page.Event evt) {
    if (evt.getType() == Page.Event.Type.REGION_STOPPED_CHANGING) {
      updateSplitColumnActions();
      revalidate();
      repaint();
    }
  }

  public abstract class AutoMultiEditAction extends ActionWithButton {
    public AutoMultiEditAction(String name, String iconName) {
      super(name, iconName);
    }
    public abstract List<Table.Edit> autoMultiEdit();
    @Override public void actionPerformed(ActionEvent evt) {
      List<Table.Edit> edits = autoMultiEdit();
      // can only do this once
      // TODO? re-enable if all these edits are later undone
      setEnabled(false);
      // report each edit
      for (Table.Edit e : edits) {
	module.reportEdit(e, false);
      }
    }
  }

  public class AutoSplitColumnsAction extends AutoMultiEditAction {
    public AutoSplitColumnsAction() {
      super("Auto-Split Columns", "auto-split-columns");
    }

    @Override public List<Table.Edit> autoMultiEdit() {
      return table.autoSplitColumns();
    }
  }

  public class AutoMergeCellsAction extends AutoMultiEditAction {
    public AutoMergeCellsAction() {
      super("Auto-Merge Cells", "auto-merge-cells");
    }

    @Override public List<Table.Edit> autoMultiEdit() {
      return table.autoMergeCells();
    }
  }

  public class SaveAction extends ActionWithButton {
    String format;
    public SaveAction(String format) {
      super("Save " + format.replaceFirst("^text/", "") + "...",
            "save-" + format.replaceFirst("^text/", ""));
      this.format = format;
    }

    @Override public void actionPerformed(ActionEvent evt) {
      JFileChooser fc = new JFileChooser(module.curDir);
      fc.setSelectedFile(new File(table.getID() + format.replaceFirst("^text/", ".")));
      int status = fc.showSaveDialog(getTopLevelAncestor());
      module.curDir = fc.getCurrentDirectory().toString();
      if (status == JFileChooser.APPROVE_OPTION) { // user chose a file
	File f = fc.getSelectedFile();
	try {
	  table.write(format, f);
	  module.tableSaved(table, f, format);
	} catch (IOException ex) {
	  System.err.println("Failed to write " + table.getID() + " to " + format + " file " + f.toString());
	}
      }
    }
  }

  public class UndoRedoAction extends ActionWithButton {
    boolean isRedo;
    public UndoRedoAction(boolean isRedo) {
      super(isRedo ? "Redo" : "Undo",
            isRedo ? "redo" : "undo");
      this.isRedo = isRedo;
    }
    @Override public void actionPerformed(ActionEvent evt) {
      try {
	if (isRedo) {
	  table.redo();
	} else {
	  table.undo();
	}
      } catch (CWCException ex) {
	throw new RuntimeException("edit failed unexpectedly", ex);
      } catch (Table.BadEdit ex) {
	throw new RuntimeException("edit failed unexpectedly", ex);
      }
      if (isRedo) {
	module.reportEdit(table.undoHistory.get(table.undoHistory.size()-1),
			  false);
      } else {
	module.reportEdit(table.redoHistory.get(0), true);
      }
    }
  }

  public class UndoMergeCellsAction extends ActionWithButton implements TableSelectionListener {
    List<Table.Undoable> edits;
    public UndoMergeCellsAction() {
      super("Undo Merge Cells", "undo-merge-cells");
      edits = new ArrayList<Table.Undoable>();
    }

    @Override public void actionPerformed(ActionEvent evt) {
      try {
	table.undo(edits);
      } catch (CWCException ex) {
	throw new RuntimeException("edit failed unexpectedly", ex);
      } catch (Table.BadEdit ex) {
	throw new RuntimeException("edit failed unexpectedly", ex);
      }
      for (Table.Undoable edit : edits) {
	// NOTE: we report undoing the version of the edit to be added to the
	// redo list, so that the row/col indices reflect the current state of
	// the table
	module.reportEdit(edit.redo, true);
      }
    }

    //// TableSelectionListener ////

    @Override public void valueChanged(TableSelection sel) {
      edits.clear();
      if (!sel.isEmpty()) {
	// find all undoable merge cells edits in the selection
	for (int row = sel.firstRow; row <= sel.lastRow; row++) {
	  for (int col = sel.firstCol; col <= sel.lastCol; col++) {
	    Table.Undoable edit = table.getUndoableMergeCellsAt(row, col);
	    if (edit != null)
	      edits.add(edit);
	  }
	}
	// sort edits from latest to earliest in table.history, so that we undo
	// them in the right order
	edits.sort(new Comparator<Table.Undoable>() {
	  @Override public int compare(Table.Undoable a, Table.Undoable b) {
	    // NOTE: we know the edits are in the history, so these indexOf
	    // calls never return -1
	    int ai = table.history.indexOf(a.undo),
	        bi = table.history.indexOf(b.undo);
	    return Integer.compare(bi, ai);
	  }
	});
      }
      setEnabled(!edits.isEmpty());
    }
    @Override public void valueStoppedChanging(TableSelection sel) {}
  }
}
