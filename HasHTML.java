package TRIPS.PDFExtractor;

import technology.tabula.HasText;

/** Like tabula's HasText, but for HTML contents. */
public interface HasHTML extends HasText {
  /** Append the HTML representation of the contents of this to the given
   * HTMLBuilder, and return it.
   */
  HTMLBuilder getHTML(HTMLBuilder out);
}
