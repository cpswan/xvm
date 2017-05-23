package org.xvm.proto.op;

import org.xvm.proto.Frame;
import org.xvm.proto.ObjectHandle;
import org.xvm.proto.OpCallable;
import org.xvm.proto.ObjectHandle.ExceptionHandle;
import org.xvm.proto.TypeCompositionTemplate.FunctionTemplate;

import org.xvm.proto.template.xFunction.FunctionHandle;

/**
 * CALL_10 rvalue-function, rvalue-param
 *
 * @author gg 2017.03.08
 */
public class Call_10 extends OpCallable
    {
    private final int f_nFunctionValue;
    private final int f_nArgValue;

    public Call_10(int nFunction, int nArg)
        {
        f_nFunctionValue = nFunction;
        f_nArgValue = nArg;
        }

    @Override
    public int process(Frame frame, int iPC)
        {
        if (f_nFunctionValue == A_SUPER)
            {
            return callSuper10(frame, f_nArgValue);
            }

        try
            {
            ObjectHandle hArg = frame.getArgument(f_nArgValue);
            if (hArg == null)
                {
                return R_WAIT;
                }

            if (f_nFunctionValue < 0)
                {
                FunctionTemplate function = getFunctionTemplate(frame, -f_nFunctionValue);

                ObjectHandle[] ahVar = new ObjectHandle[function.m_cVars];
                ahVar[0] = hArg;

                return frame.call1(function, null, ahVar, Frame.R_UNUSED);
                }

            FunctionHandle hFunction = (FunctionHandle) frame.getArgument(f_nFunctionValue);
            if (hFunction == null)
                {
                return R_WAIT;
                }

            ObjectHandle[] ahVar = new ObjectHandle[hFunction.getVarCount()];
            ahVar[0] = hArg;

            return hFunction.call1(frame, ahVar, Frame.R_UNUSED);
            }
        catch (ExceptionHandle.WrapperException e)
            {
            frame.m_hException = e.getExceptionHandle();
            return R_EXCEPTION;
            }
        }
    }
