package apoc.number;

import org.apache.commons.lang.StringUtils;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.UserFunction;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParseException;
import java.util.Arrays;
import java.util.Locale;

/**
 * 
 * @since 25.8.2016
 * @author inserpio
 */
public class Numbers {

  @UserFunction
  @Description("apoc.number.format(number)  | format a long or double using the default system pattern and language to produce a string")
  public String format(final @Name("number") Object value,@Name(value = "pattern",defaultValue = "") String pattern, @Name(value = "lang", defaultValue = "") String lang) {
    Number number = validateNumberParam(value);
    if (number == null) return null;
    DecimalFormat format = buildFormatter(pattern, lang);
    if (format == null) return null;
    return format.format(number);
  }

  @UserFunction("apoc.number.parseInt")
  @Description("apoc.number.parseInt(text)  | parse a text using the default system pattern and language to produce a long")
  public Long parseInt(final @Name("text") String text, @Name(value = "pattern",defaultValue = "") String pattern, @Name(value = "lang",defaultValue = "") String lang) {
    Number res = parseNumber(text, pattern, lang);
    return res == null ? null : res.longValue();
  }

  private Number parseNumber(@Name("text") String text, @Name(value = "pattern", defaultValue = "") String pattern, @Name(value = "lang", defaultValue = "") String lang) {
    if (StringUtils.isBlank(text)) {
      return null;
    }
    try {
      return buildFormatter(pattern, lang).parse(text);
    } catch (ParseException e) {
      return null;
    }
  }

  @UserFunction("apoc.number.parseFloat")
  @Description("apoc.number.parseFloat(text)  | parse a text using the default system pattern and language to produce a double")
  public Double parseFloat(final @Name("text") String text, @Name(value = "pattern",defaultValue = "") String pattern, @Name(value = "lang",defaultValue = "") String lang) {
    Number res = parseNumber(text, pattern, lang);
    return res == null ? null : res.doubleValue();
  }

  private Number validateNumberParam(Object number) {
    return number instanceof Number ? (Number) number : null;
  }

  private DecimalFormat buildFormatter(String pattern, String language) {
    if ("".equals(pattern)) pattern = null;
    if ("".equals(language)) language = "en";

    Locale locale = null;
    if (language != null) {
      if (!Arrays.asList(Locale.getISOLanguages()).contains(language)) {
        return null; // throw new IllegalArgumentException("Unrecognized language value: '" + language + "' isn't a valid ISO language");
      }
      locale = new Locale(language);
    }
    
    DecimalFormatSymbols symbols = null;
    if (locale != null) {
      symbols = new DecimalFormatSymbols(locale);
    }

    DecimalFormat format = null;
    if (pattern == null && symbols == null) {
      format = new DecimalFormat();
    }
    else if (pattern == null) {
      format = new DecimalFormat();
      format.setDecimalFormatSymbols(symbols);
    }
    else if (symbols == null) {
      format = new DecimalFormat(pattern);
    }
    else {
      format = new DecimalFormat(pattern, symbols);
    }
    
    return format;
  }
}
