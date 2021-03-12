MAIN=PDFExtractor
PACKAGE=TRIPS.PDFExtractor
TABULA_VERSION=1.0.2
TABULA_JAR=tabula-$(TABULA_VERSION)-jar-with-dependencies.jar
USES=TRIPS.TripsModule TRIPS.KQML TRIPS.util TRIPS.util.cwc $(TABULA_JAR:.jar=)
JFLAGS=-cp $(CLASSPATH):$(TABULA_JAR):. -Xlint:unchecked
ifeq ($(shell uname),Linux)
EXTRA_JAVA_FLAGS=-Dsun.java2d.xrender=false
endif

SRCS=$(MAIN).java \
	ActionWithButton.java \
	Cell.java \
	CellProperties.java \
	Document.java \
	DocumentMenu.java \
	EditedCell.java \
	HasHTML.java \
	HasID.java \
	HasSortOrders.java \
	HelpButton.java \
	HTMLBuilder.java \
	IntervalRelation.java \
	LearningGUI.java \
	MergedCell.java \
	Note.java \
	Page.java \
	PDFPane.java \
	Region.java \
	ResultSet.java \
	SpanTable.java \
	SyntheticCell.java \
	Table.java \
	TableEditMenu.java \
	TableSelection.java \
	TableSelectionListener.java \
	TableSelectionModel.java \
	TextChunker.java \
	TextMatch.java \
	VisibilityFilter.java

CLASSES=$(SRCS:.java=.class) \
	CellProperties$$1.class \
	CellProperties$$Editor.class \
	CellProperties$$Editor$$Verifier.class \
	CellProperties$$Type.class \
	DocumentMenu$$OpenAction.class \
	DocumentMenu$$DetectTableAction.class \
	DocumentMenu$$ParseTableAction.class \
	HasID$$NextID.class \
	LearningGUI$$1.class \
	LearningGUI$$2.class \
	LearningGUI$$3.class \
	LearningGUI$$3$$1.class \
	LearningGUI$$4.class \
	LearningGUI$$5.class \
	LearningGUI$$6.class \
	LearningGUI$$7.class \
	LearningGUI$$8.class \
	LearningGUI$$9.class \
	LearningGUI$$10.class \
	LearningGUI$$11.class \
	LearningGUI$$AnswerReceiver.class \
	LearningGUI$$Promise.class \
	LearningGUI$$RequestSender.class \
	LearningGUI$$Rule.class \
	Page$$Event.class \
	Page$$Event$$Type.class \
	Page$$Listener.class \
	Page$$Paragraph.class \
	Page$$ParagraphLineComparator.class \
	PDFExtractor$$1.class \
	PDFExtractor$$2.class \
	PDFExtractor$$3.class \
	PDFExtractor$$4.class \
	PDFExtractor$$5.class \
	PDFExtractor$$6.class \
	PDFExtractor$$7.class \
	PDFPane$$Listener.class \
	Region$$1.class \
	Region$$2.class \
	Region$$Coord.class \
	Region$$NamedColor.class \
	Region$$Order.class \
	Region$$Order$$Key.class \
	Region$$Order$$SimpleKey.class \
	Region$$Order$$DistanceKey.class \
	Region$$Order$$ColorKey.class \
	Region$$Relation.class \
	Region$$Source.class \
	Table$$1.class \
	Table$$2.class \
	Table$$AddCaption.class \
	Table$$AddFootnote.class \
	Table$$AutoSplitColumn.class \
	Table$$BadEdit.class \
	Table$$CellCoordinateComparator.class \
	Table$$DeleteColumns.class \
	Table$$DeleteFootnote.class \
	Table$$DeleteRows.class \
	Table$$Edit.class \
	Table$$EditCaption.class \
	Table$$EditCell.class \
	Table$$EditCells.class \
	Table$$EditFootnote.class \
	Table$$EditWithDialog.class \
	Table$$MergeCells.class \
	Table$$MergeColumns.class \
	Table$$MergeRows.class \
	Table$$MergeTables.class \
	Table$$RegionAlreadyUsed.class \
	Table$$SelectColumns.class \
	Table$$SelectRows.class \
	Table$$SplitColumn.class \
	Table$$Undoable.class \
	TableEditMenu$$AutoMergeCellsAction.class \
	TableEditMenu$$AutoMultiEditAction.class \
	TableEditMenu$$AutoSplitColumnsAction.class \
	TableEditMenu$$EditAction.class \
	TableEditMenu$$EditCaptionAction.class \
	TableEditMenu$$MergeTablesAction.class \
	TableEditMenu$$RegionEditAction.class \
	TableEditMenu$$SaveAction.class \
	TableEditMenu$$SelectionEditAction.class \
	TableEditMenu$$UndoMergeCellsAction.class \
	TableEditMenu$$UndoMergeCellsAction$$1.class \
	TableEditMenu$$UndoRedoAction.class \
	TextChunker$$1.class \
	TextMatch$$1.class \
	TextMatch$$Order.class \
	TextMatch$$Order$$Key.class \
	TextMatch$$Searchable.class \
	VisibilityFilter$$Renderer.class \
	VisibilityFilter$$ShownGlyph.class

XTRA = \
	README.html \
	images/add-caption.png \
	images/add-footnote.png \
	images/auto-merge-cells.png \
	images/auto-split-columns.png \
	images/delete-columns.png \
	images/delete-footnote.png \
	images/delete-rows.png \
	images/edit-caption.png \
	images/edit-cell.png \
	images/edit-cells.png \
	images/edit-footnote.png \
	images/merge-cells.png \
	images/merge-columns.png \
	images/merge-rows.png \
	images/merge-tables.png \
	images/redo.png \
	images/save-csv.png \
	images/save-html.png \
	images/split-column.png \
	images/undo.png \
	images/undo-merge-cells.png

all:: $(TABULA_JAR)

install:: $(TABULA_JAR)

CONFIGDIR=trips-base/src/config
include $(CONFIGDIR)/java/prog.mk

# the following two rules added for standalone git version
# override TRIPS symlink in this dir to point to trips-base/src/ instead of ../
# and make a symlink back up here
TRIPS-stamp:: Makefile
	rm -f TRIPS trips-base/src/PDFExtractor
	ln -s trips-base/src/ TRIPS
	(cd trips-base/src/ && ln -s ../../ PDFExtractor)
	date >TRIPS-stamp

# install/clean dependencies from TRIPS
install clean::
	cd trips-base/src/KQML && make $@
	cd trips-base/src/TripsModule && make $@
	cd trips-base/src/util/cwc && make $@

$(TABULA_JAR): technology/tabula/PublicRSI.java
	curl -L -O https://github.com/tabulapdf/tabula-java/releases/download/v$(TABULA_VERSION)/tabula-$(TABULA_VERSION)-jar-with-dependencies.jar
	$(JAVAC) $(JFLAGS) technology/tabula/PublicRSI.java
	$(JAR) -uf $(TABULA_JAR) technology/tabula/PublicRSI.class

install::
	$(INSTALL_DATA) $(TABULA_JAR) $(etcdir)/java

# override the version of this rule from ../config/java/common.mk to add JFLAGS
# (to get the jar file in the classpath) and -Xdoclint:none (to get rid of
# annoying warnings about missing @param docs)
doc/index.html: $(SRCS)
	test -d doc || mkdir doc
	$(JAVADOC) $(JFLAGS) -Xdoclint:none -d doc -private $(PACKAGE)

distclean:: clean
	rm -f $(TABULA_JAR)

print-tabula-jar:
	$(info $(TABULA_JAR))
