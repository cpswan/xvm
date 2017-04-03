package org.xvm.proto.op;

import org.xvm.proto.Frame;
import org.xvm.proto.Op;

/**
 * MOVE lvalue-dest, rvalue-src
 *
 * @author gg 2017.03.08
 */
public class Move extends Op
    {
    final private int f_nToValue;
    final private int f_nFromValue;

    public Move(int nTo, int nFrom)
        {
        f_nToValue = nTo;
        f_nFromValue = nFrom;
        }

    @Override
    public int process(Frame frame, int iPC)
        {
        frame.f_ahVar[f_nToValue] = frame.f_ahVar[f_nFromValue];

        return iPC + 1;
        }
    }
