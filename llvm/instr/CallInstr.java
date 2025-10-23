package llvm.instr;

import llvm.Builder;
import llvm.Function;
import llvm.GlobalString;
import llvm.GlobalValue;
import llvm.ReturnType;
import llvm.Value;
import llvm.ValueType;
import llvm.constant.Constant;

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
}
