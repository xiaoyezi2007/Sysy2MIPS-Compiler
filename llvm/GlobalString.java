package llvm;

import llvm.constant.Constant;
import llvm.constant.ConstantString;
import mips.MipsBuilder;
import mips.MipsString;

public class GlobalString extends GlobalValue{
    Constant value = null;
    private int len;

    public GlobalString(Constant value, int len) {
        super(ValueType.GLOBAL_STRING, new IRType("string"), Builder.getStringName());
        this.value = value;
        this.len = len;
    }

    public int getLength() {
        return len + 1;
    }

    @Override
    public void toMips() {
        String string = value.getName();
        String newString = "";
        for (int i = 0; i < string.length(); i++) {
            if (i+2<string.length() && string.charAt(i) == '\\' && string.charAt(i+1) == '0' && string.charAt(i+2) == 'A') {
                newString += "\\n";
                i+=2;
            }
            else {
                newString += string.charAt(i);
            }
        }
        MipsBuilder.addString(new MipsString(getName().substring(1), newString));
    }

    public void print() {
        System.out.println(name+" = private unnamed_addr constant ["+getLength()+
            " x i8] c\""+value.name+"\\00\", align 1");
    }
}
