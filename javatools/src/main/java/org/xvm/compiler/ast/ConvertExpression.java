package org.xvm.compiler.ast;


import org.xvm.asm.Argument;
import org.xvm.asm.Constant;
import org.xvm.asm.ErrorListener;
import org.xvm.asm.MethodStructure;
import org.xvm.asm.MethodStructure.Code;
import org.xvm.asm.Op;
import org.xvm.asm.Register;

import org.xvm.asm.constants.MethodConstant;
import org.xvm.asm.constants.TypeConstant;

import org.xvm.asm.op.Invoke_01;
import org.xvm.asm.op.JumpFalse;
import org.xvm.asm.op.Label;
import org.xvm.asm.op.Var;


/**
 * A type conversion expression. This converts a value from the sub-expression into a value of a
 * different type.
 */
public class ConvertExpression
        extends SyntheticExpression
    {
    // ----- constructors --------------------------------------------------------------------------

    /**
     * Construct a ConvertExpression that will convert a result from the passed expression to a
     * different type using the provided method.
     *
     * @param expr    the expression that yields the raw results
     * @param iVal    the index of the raw result that needs conversion
     * @param idConv  the method of conversion
     * @param errs    the ErrorListener to log errors to
     */
    public ConvertExpression(Expression expr, int iVal, MethodConstant idConv, ErrorListener errs)
        {
        super(expr);

        assert iVal >= 0 && iVal < expr.getValueCount();
        assert idConv != null;
        assert idConv.getRawParams().length == 0
            || (idConv.getComponent() instanceof MethodStructure method)
                && method.getParamCount()-method.getDefaultParamCount() == 0;
        assert idConv.getRawReturns().length > 0;
        assert !idConv.getComponent().isStatic();

        m_iVal   = iVal;
        m_idConv = idConv;

        TypeConstant type = idConv.getRawReturns()[0];
        if (expr.isSingle())
            {
            Constant val = null;
            if (expr.isConstant())
                {
                // determine if compile-time conversion is supported
                val = convertConstant(expr.toConstant(), type);
                }

            finishValidation(null, null, type, expr.getTypeFit().addConversion(), val, errs);
            }
        else
            {
            TypeConstant[] aTypes = expr.getTypes().clone();
            aTypes[iVal] = type;

            Constant[] aVals = null;
            if (expr.isConstant())
                {
                Constant[] aOldVals = expr.toConstants();
                Constant   constNew = convertConstant(aOldVals[iVal], type);
                if (constNew != null)
                    {
                    aVals = aOldVals.clone();
                    aVals[iVal] = constNew;
                    }
                }

            finishValidations(null, null, aTypes, expr.getTypeFit().addConversion(), aVals, errs);
            }
        }


    // ----- accessors -----------------------------------------------------------------------------

    /**
     * @return the index of the expression value within a multi-value expression, or 0 within a
     *         single-value expression
     */
    public int getValueIndex()
        {
        return m_iVal;
        }

    /**
     * @return the method id that specifies the conversion method
     */
    public MethodConstant getConversionMethod()
        {
        return m_idConv;
        }


    // ----- Expression compilation ----------------------------------------------------------------

    @Override
    public boolean isConditionalResult()
        {
        return expr.isConditionalResult();
        }

    @Override
    protected boolean hasMultiValueImpl()
        {
        return true;
        }

    @Override
    public TypeConstant getImplicitType(Context ctx)
        {
        return getType();
        }

    @Override
    public TypeConstant[] getImplicitTypes(Context ctx)
        {
        return getTypes();
        }

    @Override
    protected Expression validate(Context ctx, TypeConstant typeRequired, ErrorListener errs)
        {
        return this;
        }

    @Override
    protected Expression validateMulti(Context ctx, TypeConstant[] atypeRequired, ErrorListener errs)
        {
        return this;
        }

    @Override
    public void generateVoid(Context ctx, Code code, ErrorListener errs)
        {
        expr.generateVoid(ctx, code, errs);
        }

    @Override
    public void generateAssignment(Context ctx, Code code, Assignable LVal, ErrorListener errs)
        {
        if (m_iVal != 0)
            {
            getUnderlyingExpression().generateAssignment(ctx, code, LVal, errs);
            return;
            }

        if (isConstant())
            {
            super.generateAssignment(ctx, code, LVal, errs);
            return;
            }

        // get the value to be converted
        Argument argIn = getUnderlyingExpression().generateArgument(ctx, code, true, true, errs);

        // determine the destination of the conversion
        if (LVal.isLocalArgument())
            {
            code.add(new Invoke_01(argIn, m_idConv, LVal.getLocalArgument()));
            }
        else
            {
            Register regResult = new Register(getType(), null, Op.A_STACK);
            code.add(new Invoke_01(argIn, m_idConv, regResult));
            LVal.assign(regResult, code, errs);
            }
        }

    @Override
    public void generateAssignments(Context ctx, Code code, Assignable[] aLVal, ErrorListener errs)
        {
        int cVals = aLVal.length;
        if (cVals == 1)
            {
            generateAssignment(ctx, code, aLVal[0], errs);
            return;
            }

        if (m_iVal >= cVals)
            {
            getUnderlyingExpression().generateAssignments(ctx, code, aLVal, errs);
            return;
            }

        if (isConstant())
            {
            super.generateAssignments(ctx, code, aLVal, errs);
            return;
            }

        // replace the LVal to convert into with a temp, and ask the underlying expression to fill
        // in the resulting set of LVals, and then convert that one value
        int          iVal      = m_iVal;
        Assignable[] aLValTemp = aLVal.clone();

        Register regTemp = code.createRegister(getUnderlyingExpression().getTypes()[iVal]);
        code.add(new Var(regTemp));

        aLValTemp[iVal] = new Assignable(regTemp);

        // create a temporary to hold the Boolean result for a conditional call, if necessary
        boolean  fCond   = isConditionalResult() && iVal > 0;
        Register regCond = null;
        Label    lblSkip = new Label("skip_conv");
        if (fCond)
            {
            Assignable aLValCond = aLValTemp[0];
            if (aLValCond.isNormalVariable())
                {
                regCond = aLValCond.getRegister();
                }
            else
                {
                regCond = code.createRegister(pool().typeBoolean());
                code.add(new Var(regCond));
                aLValTemp[0] = new Assignable(regCond);
                }
            }

        // generate the pre-converted value
        getUnderlyingExpression().generateAssignments(ctx, code, aLValTemp, errs);

        // skip the conversion if the conditional result was False
        if (fCond)
            {
            if (aLVal[0] != aLValTemp[0])
                {
                aLVal[0].assign(regCond, code, errs);
                }
            code.add(new JumpFalse(regCond, lblSkip));
            }

        // determine the destination of the conversion
        Assignable LVal = aLVal[iVal];
        if (LVal.isLocalArgument())
            {
            code.add(new Invoke_01(regTemp, m_idConv, LVal.getLocalArgument()));
            }
        else
            {
            Register regResult = new Register(getTypes()[iVal], null, Op.A_STACK);
            code.add(new Invoke_01(regTemp, m_idConv, regResult));
            LVal.assign(regResult, code, errs);
            }

        if (fCond)
            {
            code.add(lblSkip);
            }
        }

    @Override
    public Assignable[] generateAssignables(Context ctx, Code code, ErrorListener errs)
        {
        return expr.generateAssignables(ctx, code, errs);
        }


    // ----- debugging assistance ------------------------------------------------------------------

    @Override
    public String toString()
        {
        return getUnderlyingExpression().toString() + '.' + m_idConv.getName() +
            (isValidated()
                    ? '<' + getType().getValueString() + ">()"
                    : "<?>()");
        }


    // ----- fields --------------------------------------------------------------------------------

    /**
     * The expression value index.
     */
    private final int m_iVal;

    /**
     * The conversion method.
     */
    private final MethodConstant m_idConv;
    }