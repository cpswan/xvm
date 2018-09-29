package org.xvm.compiler.ast;


import java.lang.reflect.Field;

import org.xvm.asm.ErrorListener;
import org.xvm.asm.MethodStructure.Code;
import org.xvm.asm.Argument;
import org.xvm.asm.Register;

import org.xvm.asm.constants.TypeConstant;

import org.xvm.asm.op.Label;
import org.xvm.asm.op.Throw;

import org.xvm.compiler.Token;


/**
 * A "throw expression" is a non-completing expression that throws an exception.
 */
public class ThrowExpression
        extends Expression
    {
    // ----- constructors --------------------------------------------------------------------------

    public ThrowExpression(Token keyword, Expression expr)
        {
        this.keyword = keyword;
        this.expr    = expr;
        }


    // ----- accessors -----------------------------------------------------------------------------

    /**
     * @return the expression for the exception being thrown
     */
    public Expression getException()
        {
        return expr;
        }

    @Override
    public long getStartPosition()
        {
        return keyword.getStartPosition();
        }

    @Override
    public long getEndPosition()
        {
        return expr.getEndPosition();
        }

    @Override
    protected Field[] getChildFields()
        {
        return CHILD_FIELDS;
        }


    // ----- compilation ---------------------------------------------------------------------------

    @Override
    protected boolean hasMultiValueImpl()
        {
        return true;
        }

    @Override
    public TypeConstant getImplicitType(Context ctx)
        {
        return null;
        }

    @Override
    public TypeConstant[] getImplicitTypes(Context ctx)
        {
        return TypeConstant.NO_TYPES;
        }

    @Override
    public TypeFit testFit(Context ctx, TypeConstant typeRequired)
        {
        return TypeFit.Fit;
        }

    @Override
    public TypeFit testFitMulti(Context ctx, TypeConstant[] atypeRequired)
        {
        return TypeFit.Fit;
        }

    @Override
    protected Expression validate(Context ctx, TypeConstant typeRequired, ErrorListener errs)
        {
        boolean fValid = validateThrow(ctx, errs);
        TypeFit fit    = fValid ? TypeFit.Fit : TypeFit.NoFit;

        // it doesn't really matter what type we choose here, but finishValidation() requires one
        TypeConstant typeActual = typeRequired == null ? pool().typeException() : typeRequired;
        return finishValidation(typeRequired, typeActual, fit, null, errs);
        }

    @Override
    protected Expression validateMulti(Context ctx, TypeConstant[] atypeRequired, ErrorListener errs)
        {
        boolean fValid = validateThrow(ctx, errs);
        TypeFit fit    = fValid ? TypeFit.Fit : TypeFit.NoFit;
        return finishValidations(atypeRequired, atypeRequired, fit, null, errs);
        }

    protected boolean validateThrow(Context ctx, ErrorListener errs)
        {
        // validate the throw value expressions
        Expression exprNew = expr.validate(ctx, pool().typeException(), errs);
        if (exprNew != expr)
            {
            if (exprNew == null)
                {
                return false;
                }
            expr = exprNew;
            }
        return true;
        }

    @Override
    public boolean isRuntimeConstant()
        {
        return true;
        }

    @Override
    public boolean isAborting()
        {
        return true;
        }

    @Override
    public boolean isShortCircuiting()
        {
        return expr.isShortCircuiting();
        }

    @Override
    public void generateVoid(Context ctx, Code code, ErrorListener errs)
        {
        generateThrow(ctx, code, errs);
        }

    @Override
    public Argument generateArgument(
            Context ctx, Code code, boolean fLocalPropOk, boolean fUsedOnce, ErrorListener errs)
        {
        generateThrow(ctx, code, errs);
        return generateBlackHole(getValueCount() == 0 ? pool().typeObject() : getType());
        }

    @Override
    public Argument[] generateArguments(
            Context ctx, Code code, boolean fLocalPropOk, boolean fUsedOnce, ErrorListener errs)
        {
        generateThrow(ctx, code, errs);

        TypeConstant[] aTypes = getTypes();
        int cArgs = aTypes.length;
        Register[] aArgs = new Register[cArgs];
        for (int i = 0; i < cArgs; ++i)
            {
            aArgs[i] = generateBlackHole(aTypes[i]);
            }
        return aArgs;
        }

    @Override
    public void generateConditionalJump(
            Context ctx, Code code, Label label, boolean fWhenTrue, ErrorListener errs)
        {
        generateThrow(ctx, code, errs);
        }

    /**
     * Generate the actual code for the throw.
     */
    protected void generateThrow(Context ctx, Code code, ErrorListener errs)
        {
        code.add(new Throw(expr.generateArgument(ctx, code, true, true, errs)));
        }


    // ----- debugging assistance ------------------------------------------------------------------

    @Override
    public String toString()
        {
        return "throw " + expr.toString() + ';';
        }

    @Override
    public String getDumpDesc()
        {
        return toString();
        }


    // ----- fields --------------------------------------------------------------------------------

    protected Token      keyword;
    protected Expression expr;

    private static final Field[] CHILD_FIELDS = fieldsForNames(ThrowExpression.class, "expr");
    }
