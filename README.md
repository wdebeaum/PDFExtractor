# PDFExtractor #

PDFExtractor displays pages of PDF files, allows the user to select rectangular regions within those pages, extracts and searches text and tabular data within those regions, displays the extracted data tables, lets the user edit those tables to improve the quality of the extractions, and saves the tables as CSV or HTML. It uses [Tabula](https://tabula.technology/) (technically its backend, [tabula-java](https://github.com/tabulapdf/tabula-java)) for table extraction, and [Apache PDFBox](https://pdfbox.apache.org/) for reading and drawing PDF files.

This file is the README for the standalone version of PDFExtractor. The main documentation in [README.html](README.html) is written in the context of TRIPS. For the standalone version, `$TRIPS_BASE` is `trips-base/`.

## Build instructions ##

First, make sure you have a java compiler installed. Then build PDFExtractor itself. You can tell the configure script where various java-related executables are on your system if they're in non-standard locations:

    ./configure [--with-java=/path/to/java] [--with-javac=/path/to/javac] [--with-jar=/path/to/jar]
    make
    make install # installs to trips-base/etc/ and trips-base/bin/

## Run instructions ##

For standalone usage, you should give PDFExtractor the `-standalone` option:

    ./trips-base/bin/PDFExtractor -standalone

For the details of how to use it in this and other modes, see [README.html](README.html).

## How is PDFExtractor different from Tabula? ##

While PDFExtractor uses the same backend as Tabula (tabula-java), it is different, and better in some ways:

 - PDFExtractor allows you to edit the table after it is extracted, in order to correct mistakes that Tabula's extractor made, or just to add more information. One important example is that when one cell (typically a heading) spans multiple columns, Tabula usually extracts those columns as only one column. PDFExtractor lets you draw more column boundaries in the original document, and split such columns.

 - PDFExtractor represents more features of tables than Tabula does, such as spanning cells (see above), headings, captions, footnotes, superscripts, and annotations on cells.

 - PDFExtractor lets you save tables as HTML files, in order to preserve those features. You can open these files in a web browser to see all these features, as well as metadata from the original PDF document. Or you can open them in a spreadsheet program to see just the heading and data cells (without captions, footnotes, annotations, or document metadata). You can also still save tables as CSV files, but they will lack all the new features.

 - PDFExtractor can do some table editing automatically. It automatically deletes the empty columns that Tabula sometimes produces, and it can, on request, attempt to automatically split columns and merge cells.

 - PDFExtractor filters out invisible parts of PDF pages, which can interfere with table detection and extraction. These parts are invisible because they're drawn outside of the clipping path, so a document author might not even realize they're there, but Tabula can "see" them, and get confused.

 - PDFExtractor uses a different algorithm for building up lines and paragraphs of text from the individual positioned glyphs within a rectangular region of a page. Tabula sorts the glyphs in the region in an "ill defined order" (which is roughly reading order based on glyph bounding boxes), and then incrementally adds each glyph to the end of the last line, and attempts to detect line breaks. PDFExtractor holistically assigns each new glyph to the line it best fits in, creating or merging lines as needed, and then sorts the glyphs within each line by their center X coordinate. This often works better, especially in the presence of superscripts.

## Licensing ##

PDFExtractor is licensed using the [GPL 2+](http://www.gnu.org/licenses/old-licenses/gpl-2.0.en.html) (see `LICENSE.txt`):

PDFExtractor - interactively extract data from PDF documents
Copyright (C) 2020  Institute for Human & Machine Cognition

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation; either version 2
of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
