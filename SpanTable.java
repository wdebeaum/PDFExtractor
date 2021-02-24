package TRIPS.PDFExtractor;

import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Dimension;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.LineBorder;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import technology.tabula.RectangularTextContainer;
import TRIPS.util.cwc.CWCException;

/** Replacement for JTable that uses GridBagLayout to support rowspan/colspan,
 * and displays a {@link Table}.
 */
public class SpanTable extends JPanel implements TableModelListener, TableSelectionListener, MouseListener {
  PDFExtractor module;
  Table model;
  TableSelectionModel selectionModel;
  GridBagLayout layout;
  JLabel caption;
  List<List<JLabel>> rows;

  public SpanTable(PDFExtractor module, Table model) {
    super(new GridBagLayout());
    layout = (GridBagLayout)getLayout();
    this.module = module;
    this.model = model;
    model.addTableModelListener(this);
    selectionModel = new TableSelectionModel(model);
    selectionModel.addListener(this);
    setBackground(Color.WHITE);
    makeChildren();
  }

  public TableSelectionModel getSelectionModel() { return selectionModel; }

  JLabel makeCell(String content, Color bg, int hAlign, String tooltip) {
    JLabel cell = new JLabel(content);
    cell.setBorder(new LineBorder(Color.BLACK));
    cell.setBackground(bg);
    cell.setForeground(Color.BLACK);
    cell.setOpaque(true);
    cell.setHorizontalAlignment(hAlign);
    cell.setVerticalTextPosition(SwingConstants.TOP);
    cell.setToolTipText(tooltip);
    cell.addMouseListener(this);
    return cell;
  }

  void makeChildren() {
    rows = null;
    selectionModel.clearSelection();
    removeAll();
    int numRows = model.getRowCount();
    int numCols = model.getColumnCount();
    rows = new ArrayList<List<JLabel>>(numRows);
    GridBagConstraints c = new GridBagConstraints();
    c.fill = GridBagConstraints.BOTH;
    { // caption
      c.gridx = 0;
      c.gridy = 0;
      c.gridwidth = numCols+1;
      c.gridheight = 1;
      String captionText = model.getCaption();
      if (captionText == null) {
	captionText = "";
      } else {
	captionText = "<html>" + captionText + "</html>";
      }
      caption =
        makeCell(captionText, Color.WHITE, SwingConstants.CENTER, null);
      layout.setConstraints(caption, c);
      add(caption);
    }
    // column headings
    for (int col = 0; col < numCols; col++) {
      c.gridx = col+1;
      c.gridy = 1;
      c.gridwidth = 1;
      c.gridheight = 1;
      JLabel cell =
        makeCell(Cell.colIndexToRef(col),
		 Color.LIGHT_GRAY, SwingConstants.CENTER, null);
      layout.setConstraints(cell, c);
      add(cell);
    }
    // row headings and cells
    for (int row = 0; row < numRows; row++) {
      { // row heading
	c.gridx = 0;
	c.gridy = row+2;
	c.gridwidth = 1;
	c.gridheight = 1;
	JLabel cell =
	  makeCell(Integer.toString(row+1),
		   Color.LIGHT_GRAY, SwingConstants.RIGHT, null);
	layout.setConstraints(cell, c);
	add(cell);
      }
      ArrayList<JLabel> cells = new ArrayList<JLabel>(numCols);
      rows.add(cells);
      for (int col = 0; col < numCols; col++) {
	c.gridx = col+1;
	c.gridy = row+2;
	String cellText = (String)model.getValueAt(row, col);
	Dimension cellSpan = model.getSpanAt(row, col);
	c.gridwidth = cellSpan.width;
	c.gridheight = cellSpan.height;
	if (c.gridwidth > 0 && c.gridheight > 0) {
	  RectangularTextContainer modelCell = model.getCellAt(row, col);
	  CellProperties props = Cell.getPropertiesOf(modelCell);
	  String tooltip = props.getToolTipHTML();
	  // substitute cell value in tooltip/annotation
	  if (tooltip != null)
	    tooltip = tooltip.replace("[cell]", Cell.getTextOf(modelCell));
	  JLabel cell =
	    makeCell(cellText, Color.WHITE, 
		     (props.isHeading() ?
		       SwingConstants.CENTER : SwingConstants.LEFT),
		     tooltip);
	  cells.add(cell);
	  layout.setConstraints(cell, c);
	  add(cell);
	} else {
	  cells.add(null);
	}
      }
    }
    // substitute row/col headings in templates in tooltips/annotations
    // for each Heading cell in the model
    for (int hi = 0; hi < numRows; hi++) {
      for (int hj = 0; hj < numCols; hj++) {
	RectangularTextContainer h = model.getCellAt(hi, hj);
	CellProperties hProps = Cell.getPropertiesOf(h);
	if (hProps.isHeading()) {
	  TableSelection hf = hProps.headingFor;
	  // we will substitute val in for var
	  String var = (hProps.type == CellProperties.Type.ROW_HEADING ?
			  "[row heading]" : "[column heading]");
	  String val = Cell.getTextOf(h);
	  // for each Data cell JLabel in the heading's headingFor range
	  for (int di = hf.firstRow; di <= hf.lastRow; di++) {
	    for (int dj = hf.firstCol; dj <= hf.lastCol; dj++) {
	      JLabel d = rows.get(di).get(dj); // Data cell
	      if (d != null) {
		String tooltip = d.getToolTipText();
		if (tooltip != null)
		  d.setToolTipText(tooltip.replace(var, val));
	      }
	    }
	  }
	}
      }
    }
  }

  //// TableModelListener ////

  @Override public void tableChanged(TableModelEvent evt) {
    makeChildren();
    revalidate();
    repaint();
  }

  //// TableSelectionListener ////
  
  @Override public void valueChanged(TableSelection sel) {
    if (rows == null) return; // called from makeChildren() via clearSelection()
    int numRows = model.getRowCount();
    int numCols = model.getColumnCount();
    for (int row = 0; row < numRows; row++) {
      List<JLabel> cells = rows.get(row);
      for (int col = 0; col < numCols; col++) {
	JLabel cell = cells.get(col);
	if (cell != null) {
	  boolean isSelected = sel.isSelected(row, col);
	  cell.setBackground(isSelected ? Color.BLUE : Color.WHITE);
	  cell.setForeground(isSelected ? Color.WHITE : Color.BLACK);
	}
      }
    }
  }

  @Override public void valueStoppedChanging(TableSelection sel) {}

  //// MouseListener ////
  
  @Override public void mouseClicked(MouseEvent evt) {
    // if this is a double-click
    if (SwingUtilities.isLeftMouseButton(evt) &&
        evt.getClickCount() == 2) {
      GridBagConstraints c = layout.getConstraints(evt.getComponent());
      Table.EditWithDialog ed = null;
      if (c.gridy == 0) // on a caption
	ed = model.new EditCaption();
      try {
	if (ed == null) { // on a cell? (throw if not)
	  TableSelection sel = new TableSelection(selectionModel);
	  ed = model.editCellFromSelection(sel);
	}
	// do the edit (i.e. put up the dialog)
	ed.module = module;
	model.edit(ed);
      } catch (Table.BadEdit ex) {
	// ignore; this just means the selection wasn't one cell
      } catch (CWCException ex) {
	throw new RuntimeException("unexpected exception while performing edit-cell in response to a double-click on a cell", ex);
      }
    }
  }

  @Override public void mouseEntered(MouseEvent evt) {
    GridBagConstraints c = layout.getConstraints(evt.getComponent());
    selectionModel.setLead(c.gridy > 1 ? c.gridy - 2 : 0,
			   c.gridx > 0 ? c.gridx - 1 : 0);
  }

  @Override public void mouseExited(MouseEvent evt) {}

  @Override public void mousePressed(MouseEvent evt) {
    GridBagConstraints c = layout.getConstraints(evt.getComponent());
    if (c.gridy == 0) { // caption
      // ignore
    } else if (c.gridx == 0) { // row heading
      selectionModel.setAdjusting(true, false);
      selectionModel.setSelection(c.gridy-2, 0, c.gridy-2, -1);
    } else if (c.gridy == 1) { // column heading
      selectionModel.setAdjusting(false, true);
      selectionModel.setSelection(0, c.gridx-1, -1, c.gridx-1);
    } else { // cell
      selectionModel.setAdjusting(true, true);
      selectionModel.setAnchorAndLead(c.gridy-2, c.gridx-1);
    }
  }

  @Override public void mouseReleased(MouseEvent evt) {
    selectionModel.setAdjusting(false, false);
    selectionModel.fireValueChanged();
  }
}
