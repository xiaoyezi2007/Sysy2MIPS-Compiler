package llvm;

import llvm.constant.Constant;
import llvm.constant.ConstantString;

public class GlobalString extends GlobalValue{
    Constant value = null;
    private int len;

    public GlobalString(Constant value, int len) {
        super(ValueType.GLOBAL_STRING, ReturnType.POINTER, Builder.getStringName());
        this.value = value;
        this.len = len;
    }

    public int getLength() {
        return len + 1;
    }

    public void print() {
        System.out.println(name+" = private unnamed_addr constant ["+getLength()+
            " x i8] c\""+value.name+"\\00\", align 1");
    }
}
