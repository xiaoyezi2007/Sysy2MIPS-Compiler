package llvm.instr;

import llvm.Builder;
import llvm.Function;
import llvm.GlobalString;
import llvm.GlobalValue;
import llvm.ReturnType;
import llvm.Value;
import llvm.ValueType;
import llvm.constant.Constant;
import llvm.constant.ConstantInt;
import mips.IInstr;
import mips.JInstr;
import mips.LswInstr;
import mips.Register;
import mips.Syscall;
import mips.fake.LaInstr;
import mips.fake.LiInstr;

import java.util.ArrayList;

public class CallInstr extends Instruction {
    private int ParaNum = 0;
    private boolean isReturn = true;

    public CallInstr(Function function, ArrayList<Value> paras) {
        super(ValueType.CALL_INST, function.getType(), Builder.getVarName());
        addUseValue(function);
        if (!function.isReturn()) {
            Builder.varCntMinus();
            isReturn = false;
        }
        for (Value v : paras) {
            addUseValue(v);
        }
        ParaNum = paras.size();
        Builder.addInstr(this);
    }

    @Override
    public Constant getValue() {
        Function function = (Function) getUseValue(0);
        return null;
        //return function.getValue();
    }

    @Override
    public int getSpace() {
        if (isReturn) {
            return 4;
        }
        return 0;
    }

    @Override
    public void toMips() {
        Value function = getUseValue(0);
        if (function.getName().equals("putint")) {
            Value out = getUseValue(1);
            loadToReg(out, Register.A0);
            new LiInstr(Register.V0, 1);
            new Syscall();
        }
        else if (function.getName().equals("getint")) {
            new LiInstr(Register.V0, 5);
            new Syscall();
            pushToMem(Register.V0);
        }
        else if (function.getName().equals("putstr")) {
            Value out = getUseValue(1);
            new LaInstr(Register.A0, out.getName().substring(1));
            new LiInstr(Register.V0, 4);
            new Syscall();
        }
        else {
            for (int i=1;i<=ParaNum;i++) {
                Value para = getUseValue(i);
                if (para.getType().isArray()) {
                    new IInstr("addiu", Register.A0, Register.SP, -para.getMemPos());
                }
                else {
                    loadToReg(para, Register.A0);
                }
                new LswInstr("sw", Register.A0, Register.SP,  4*i - ((Function) function).getStackSpace());
            }
            new JInstr("jal", function.getName());
            if (isReturn) {
                pushToMem(Register.V0);
            }
        }
    }

    @Override
    public void print() {
        Value function = getUseValue(0);
        ArrayList<Value> params = new ArrayList<>();
        for (int i = 1; i <= ParaNum; i++) {
            params.add(getUseValue(i));
        }
        if (function.getName().equals("putstr")) {
            int len = ((GlobalString) params.get(0)).getLength();
            System.out.println("call void @putstr(i8* getelementptr inbounds (["+len+" x i8], ["+len+
                " x i8]* "+params.get(0).getName()+ ", i64 0, i64 0))");
            return;
        }
        if (isReturn) {
            System.out.print(name + " = call i32 @" + function.getName() + "(");
        }
        else {
            System.out.print("call void @" + function.getName() + "(");
        }
        for (int i = 0; i < ParaNum; i++) {
            System.out.print(params.get(i).getTypeName()+" "+params.get(i).getName());
            if (i < ParaNum - 1) System.out.print(", ");
        }
        System.out.println(")");
    }

    @Override
    public boolean isDead() {
        return false;
    }

    @Override
    public boolean isPinned() {
        return true;
    }

    public void discardReturn() {
        isReturn = false;
    }

    public void removeArg(int idx) {
        int pos = idx + 1; // first operand is callee
        if (pos < 1 || pos >= useList.size()) {
            return;
        }
        Value arg = useList.get(pos).getValue();
        if (arg != null) {
            arg.rmUser(this);
        }
        useList.remove(pos);
        values.remove(pos);
        ParaNum--;
    }
}
