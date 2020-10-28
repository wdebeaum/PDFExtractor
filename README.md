# PDFExtractor #

PDFExtractor displays pages of PDF files, allows the user to select rectangular regions within those pages, extracts and searches text and tabular data within those regions, and displays the extracted data tables. It uses [Tabula](https://tabula.technology/) for table extraction, and [Apache PDFBox](https://pdfbox.apache.org/) for reading and drawing PDF files.

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
