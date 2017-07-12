package org.xvm.proto.op;

import org.xvm.asm.MethodStructure;

import org.xvm.proto.Frame;
import org.xvm.proto.ObjectHandle;
import org.xvm.proto.ObjectHandle.ExceptionHandle;
import org.xvm.proto.OpInvocable;
import org.xvm.proto.TypeComposition;

import org.xvm.proto.template.xFunction;
import org.xvm.proto.template.xService.ServiceHandle;

/**
 * INVOKE_N1  rvalue-target, CONST-METHOD, #params:(rvalue), lvalue-return
 *
 * @author gg 2017.03.08
 */
public class Invoke_N1 extends OpInvocable
    {
    private final int f_nTargetValue;
    private final int f_nMethodId;
    private final int[] f_anArgValue;
    private final int f_nRetValue;

    public Invoke_N1(int nTarget, int nMethodId, int[] anArg, int nRet)
        {
        f_nTargetValue = nTarget;
        f_nMethodId = nMethodId;
        f_anArgValue = anArg;
        f_nRetValue = nRet;
        }

    @Override
    public int process(Frame frame, int iPC)
        {
        try
            {
            ObjectHandle hTarget = frame.getArgument(f_nTargetValue);
            if (hTarget == null)
                {
                return R_REPEAT;
                }

            TypeComposition clz = hTarget.f_clazz;
            MethodStructure method = getMethodStructure(frame, clz, f_nMethodId);

            if (frame.f_adapter.isNative(method))
                {
                ObjectHandle[] ahArg = frame.getArguments(f_anArgValue, f_anArgValue.length);
                if (ahArg == null)
                    {
                    return R_REPEAT;
                    }
                return clz.f_template.invokeNative(frame, method, hTarget, ahArg, f_nRetValue);
                }

            ObjectHandle[] ahVar = frame.getArguments(f_anArgValue, frame.f_adapter.getVarCount(method));
            if (ahVar == null)
                {
                return R_REPEAT;
                }

            if (clz.f_template.isService() && frame.f_context != ((ServiceHandle) hTarget).m_context)
                {
                return xFunction.makeAsyncHandle(method).call1(frame, hTarget, ahVar, f_nRetValue);
                }

            return frame.call1(method, hTarget, ahVar, f_nRetValue);
            }
        catch (ExceptionHandle.WrapperException e)
            {
            frame.m_hException = e.getExceptionHandle();
            return R_EXCEPTION;
            }
        }
    }
