package org.xvm.asm.op;


import java.io.DataInput;
import java.io.IOException;

import org.xvm.asm.Constant;
import org.xvm.asm.OpMove;
import org.xvm.asm.Register;

import org.xvm.asm.constants.TypeConstant;

import org.xvm.runtime.Frame;
import org.xvm.runtime.ObjectHandle;
import org.xvm.runtime.ObjectHandle.ExceptionHandle;

import org.xvm.runtime.template.xException;


/**
 * CAST rvalue-src, lvalue-dest
 */
public class MoveCast
        extends OpMove
    {
    /**
     * Construct a CAST op for the passed arguments.
     *
     * @param argFrom  the Register to move from
     * @param regTo  the Register to move to
     */
    public MoveCast(Argument argFrom, Register regTo)
        {
        super(argFrom, regTo);
        }

    /**
     * Deserialization constructor.
     *
     * @param in      the DataInput to read from
     * @param aconst  an array of constants used within the method
     */
    public MoveCast(DataInput in, Constant[] aconst)
            throws IOException
        {
        super(in, aconst);
        }

    @Override
    public int getOpCode()
        {
        return OP_CAST;
        }

    @Override
    public int process(Frame frame, int iPC)
        {
        try
            {
            ObjectHandle hValue = frame.getArgument(m_nFromValue);
            if (hValue == null)
                {
                return R_REPEAT;
                }

            TypeConstant typeFrom = hValue.getType();
            TypeConstant typeTo   = frame.getArgumentType(m_nToValue);

            return typeFrom.isA(typeTo)
                ? frame.assignValue(m_nToValue, hValue)
                : frame.raiseException(xException.makeHandle(typeFrom.getValueString())); // TODO: use a stock exception
            }
        catch (ExceptionHandle.WrapperException e)
            {
            return frame.raiseException(e);
            }
        }
    }
