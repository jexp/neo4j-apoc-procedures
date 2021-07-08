package apoc.number.exact;

import org.apache.commons.lang3.StringUtils;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.UserFunction;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

import static java.lang.Math.pow;

/**
 * @author AgileLARUS
 *
 * @since 17 May 2017
 */
public class Exact {

	@UserFunction
	@Description("apoc.number.exact.add(stringA,stringB) - return the sum's result of two large numbers")
	public String add(@Name("stringA")String stringA, @Name("stringB")String stringB){
		if(stringA == null || stringA.isEmpty() || stringB == null || stringB.isEmpty()) return null;
		return new BigDecimal(stringA).add(new BigDecimal(stringB)).toPlainString();
	}

	@UserFunction
	@Description("apoc.number.exact.sub(stringA,stringB) - return the substraction's of two large numbers")
	public String sub(@Name("stringA")String stringA, @Name("stringB")String stringB){
		if(stringA == null || stringA.isEmpty() || stringB == null || stringB.isEmpty()) return null;
		return new BigDecimal(stringA).subtract(new BigDecimal(stringB)).toPlainString();
	}

	@UserFunction
	@Description("apoc.number.exact.mul(stringA,stringB,[prec],[roundingModel]) - return the multiplication's result of two large numbers ")
	public String mul(@Name("stringA")String stringA, @Name("stringB")String stringB, @Name(value = "precision" , defaultValue = "0")Long precision, @Name(value = "roundingMode", defaultValue = "HALF_UP")String roundingMode){
		if(stringA == null || stringA.isEmpty() || stringB == null || stringB.isEmpty()) return null;
		String s = new BigDecimal(stringA).multiply(new BigDecimal(stringB), createMathContext(precision, roundingMode)).toPlainString();
		return s;
	}

	@UserFunction
	@Description("apoc.number.exact.div(stringA,stringB,[prec],[roundingModel]) - return the division's result of two large numbers")
	public String div(@Name("stringA")String stringA, @Name("stringB")String stringB, @Name(value = "precision" , defaultValue = "0")Long precision, @Name(value = "roundingMode", defaultValue = "HALF_UP")String roundingMode){
		if(stringA == null || stringA.isEmpty() || stringB == null || stringB.isEmpty()) return null;
		return new BigDecimal(stringA).divide(new BigDecimal(stringB), createMathContext(precision, roundingMode)).toPlainString();
	}

	@UserFunction
	@Description("apoc.number.exact.toInteger(string,[prec],[roundingMode]) - return the Integer value of a large number")
	public Long toInteger(@Name("stringA")String string, @Name(value = "precision" , defaultValue = "0")Long precision, @Name(value = "roundingMode", defaultValue = "HALF_UP")String roundingMode){
		if(string == null || string.isEmpty()) return null;
			return new BigDecimal(string, createMathContextLong(precision, roundingMode)).longValue();
	}

	@UserFunction
	@Description("apoc.number.exact.toFloat(string,[prec],[roundingMode]) - return the Float value of a large number")
	public Double toFloat(@Name("stringA")String string, @Name(value = "precision" , defaultValue = "0")Long precision, @Name(value = "roundingMode", defaultValue = "HALF_UP")String roundingMode){
		if(string == null || string.isEmpty()) return null;
		return new BigDecimal(string, createMathContext(precision, roundingMode)).doubleValue();
	}

	@UserFunction
	@Description("apoc.number.exact.toExact(number) - return the exact value")
	public Long toExact(@Name("number")Long number){
		if(number == null) return null;
		return new BigDecimal(number).longValueExact();
	}

	private MathContext createMathContext(Long precision, String roundingMode){

		if (precision == null) {
			precision = Long.valueOf(0);
		}

		RoundingMode rm = RoundingMode.HALF_UP;
		if (!StringUtils.isEmpty(roundingMode) || roundingMode != null) {
			rm = RoundingMode.valueOf(roundingMode);
		}

		return new MathContext(precision.intValue(), rm);
	}

	private MathContext createMathContextLong(Long precision, String roundingMode){
		if (precision == null) {
			precision = Long.valueOf(0);
		}
		else {
			Double pow = pow(10, precision);
			precision = precision * pow.longValue();
		}
		RoundingMode rm = RoundingMode.HALF_UP;
		if (!StringUtils.isEmpty(roundingMode) || roundingMode != null) {
			rm = RoundingMode.valueOf(roundingMode);
		}

		return new MathContext(precision.intValue(), rm);
	}
}
