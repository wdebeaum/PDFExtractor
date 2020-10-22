package TRIPS.PDFExtractor;

import java.awt.Dimension;
import java.util.List;

/** Like StringBuilder, but for building (X)HTML strings, in particular tables
 * whose cells contain line breaks and superscripts. Most methods append
 * something and return this, enabling call chaining.
 */
public class HTMLBuilder {
  StringBuilder stringBuilder;
  public HTMLBuilder() {
    stringBuilder = new StringBuilder();
  }

  public String toFragmentString() {
    return stringBuilder.toString();
  }

  public String toDocumentString() {
    return "<html>" + toFragmentString() + "</html>";
  }

  public String toProperDocumentString(String title, String meta) {
    return "<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n" + meta + "<title>" + title + "</title>\n<style type=\"text/css\">\ntable { border-collapse: collapse; }\ntd, th { border: 1px solid black; }\n</style>\n</head>\n<body>" + toFragmentString() + "</body>\n</html>";
  }

  public String toTextString() {
    // FIXME? !Cell.getHTMLOf(x).toTextString().equals(Cell.getTextOf(x))
    return
      unescape(
	removeTags(
	  toFragmentString().
	  replaceAll("<(br/|table|/tr)>", "\n").
	  replaceAll("<(/td|/?sup)>", " ")
	)
      );
  }

  public String toString() { return toDocumentString(); }

  public HTMLBuilder html(String html) {
    stringBuilder.append(html);
    return this;
  }

  public HTMLBuilder html(HTMLBuilder other) {
    return html(other.toFragmentString());
  }

  public HTMLBuilder text(String text) {
    return html(escape(text));
  }

  public HTMLBuilder attr(String name, String val) {
    return html(" ").html(name).html("=\"").html(escapeAttrVal(val)).html("\"");
  }

  public HTMLBuilder br() { return html("<br/>"); }

  public HTMLBuilder lines(List<String> htmls) {
    return html(String.join("<br/>", htmls));
  }

  public HTMLBuilder textLines(String text) {
    String[] lines = text.split("\n");
    for (String line : lines) {
      text(line).br();
    }
    return this;
  }

  public HTMLBuilder sup(String text) {
    return
      html("<sup>").
      html(escape(text)).
      html("</sup>");
  }

  public HTMLBuilder beginTable() { return html("<table>"); }
  public HTMLBuilder endTable() { return html("</table>"); }

  public HTMLBuilder beginTR() { return html("<tr>"); }
  public HTMLBuilder endTR() { return html("</tr>"); }

  private HTMLBuilder beginCellCommon(Dimension span, String title, boolean isHeading) {
    html(isHeading ? "<th" : "<td");
    if (span.height != 1) attr("rowspan", ""+span.height);
    if (span.width != 1) attr("colspan", ""+span.width);
    if (title != null) attr("title", title);
    return this;
  }
  public HTMLBuilder beginCell(Dimension span, String title, boolean isHeading) {
    return beginCellCommon(span, title, isHeading).html(">");
  }
  public HTMLBuilder beginCell(Dimension span, String title, boolean isHeading, String id, String scope, String headers) {
    beginCellCommon(span, title, isHeading);
    if (id != null) attr("id", id);
    if (scope != null) attr("scope", scope);
    if (headers != null) attr("headers", headers);
    return html(">");
  }
  public HTMLBuilder endCell(boolean isHeading) {
    return html(isHeading ? "</th>" : "</td>");
  }

  public HTMLBuilder beginB() { return html("<b>"); }
  public HTMLBuilder endB() { return html("</b>"); }

  public HTMLBuilder script(String content) {
    return
      html("<script type=\"text/javascript\">\n").
      html(content). // NOTE: escaping not necessary
      html("\n</script>");
  }

  public static String escape(String text) {
    return
      text.
      replaceAll("&", "&amp;").
      replaceAll("<", "&lt;").
      replaceAll(">", "&gt;");
  }

  public static String escapeAttrVal(String text) {
    return
      text.
      replaceAll("&", "&amp;").
      replaceAll("\"", "&quot;").
      replaceAll("<", "&lt;").
      replaceAll(">", "&gt;");
  }

  public static String unescape(String html) {
    return
      html.
      replaceAll("&lt;", "<").
      replaceAll("&gt;", ">").
      replaceAll("&quot;", "\"").
      replaceAll("&amp;", "&");
  }

  public static String removeTags(String html) {
    return html.replaceAll("<[^>]*>", "");
  }
}
