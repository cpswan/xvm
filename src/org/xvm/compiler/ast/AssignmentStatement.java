package org.xvm.compiler.ast;


import java.lang.reflect.Field;

import java.util.Collections;

import org.xvm.asm.ErrorListener;

import org.xvm.asm.MethodStructure.Code;

import org.xvm.compiler.Token;

import org.xvm.compiler.ast.Expression.Assignable;


/**
 * An assignment statement specifies an l-value, an assignment operator, and an r-value.
 *
 * Additionally, this can represent the assignment portion of a "conditional declaration".
 */
public class AssignmentStatement
        extends ConditionalStatement
    {
    // ----- constructors --------------------------------------------------------------------------

    public AssignmentStatement(Expression lvalue, Token op, Expression rvalue)
        {
        this(lvalue, op, rvalue, true);
        }

    public AssignmentStatement(Expression lvalue, Token op, Expression rvalue, boolean standalone)
        {
        this.lvalue = lvalue;
        this.op     = op;
        this.rvalue = rvalue;
        this.cond   = op.getId() == Token.Id.COLON;
        this.term   = standalone;
        }


    // ----- accessors -----------------------------------------------------------------------------

    public boolean isConditional()
        {
        return op.getId() == Token.Id.COND;
        }

    @Override
    public long getStartPosition()
        {
        return lvalue.getStartPosition();
        }

    @Override
    public long getEndPosition()
        {
        return rvalue.getEndPosition();
        }

    @Override
    protected Field[] getChildFields()
        {
        return CHILD_FIELDS;
        }


    // ----- ConditionalStatement methods ----------------------------------------------------------

    @Override
    protected void split()
        {
        // there's no declaration portion, so just use a "no-op" for that statement
        long      lPos    = getStartPosition();
        Statement stmtNOP = new StatementBlock(Collections.EMPTY_LIST, lPos, lPos);
        configureSplit(stmtNOP, this);
        }


    // ----- compilation ---------------------------------------------------------------------------

    @Override
    protected boolean validate(Context ctx, ErrorListener errs)
        {
        boolean fValid = lvalue.validate(ctx, null, errs);

        // provide the l-value's type to the r-value so that it can "infer" its type as necessary,
        // and can validate that assignment can occur
        fValid &= rvalue.validate(ctx, lvalue.getImplicitType(), errs);

        return fValid;
        }

    @Override
    protected boolean emit(Context ctx, boolean fReachable, Code code, ErrorListener errs)
        {
        switch (getUsage())
            {
            case While:
            case If:
            case For:
            case Switch:
                // TODO
                throw notImplemented();

            case Standalone:
               break;
            }

        if (lvalue.isSingle() && op.getId() == Token.Id.ASN)
            {
            boolean fCompletes = fReachable;

            Assignable asnL = lvalue.generateAssignable(code, errs);

            if (fCompletes &= lvalue.isCompletable())
                {
                rvalue.generateAssignment(code, asnL, errs);

                fCompletes &= rvalue.isCompletable();
                }

            return fCompletes;
            }

        throw notImplemented();
        }


    // ----- debugging assistance ------------------------------------------------------------------

    @Override
    public String toString()
        {
        StringBuilder sb = new StringBuilder();
        sb.append(lvalue)
          .append(' ')
          .append(op.getId().TEXT)
          .append(' ')
          .append(rvalue);

        if (term)
            {
            sb.append(';');
            }

        return sb.toString();
        }

    @Override
    public String getDumpDesc()
        {
        return toString();
        }


    // ----- fields --------------------------------------------------------------------------------

    protected Expression lvalue;
    protected Token      op;
    protected Expression rvalue;
    protected boolean    cond;
    protected boolean    term;

    private static final Field[] CHILD_FIELDS = fieldsForNames(AssignmentStatement.class, "lvalue", "rvalue");
    }
