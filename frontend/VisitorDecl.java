package frontend;

import llvm.Builder;
import llvm.GlobalValue;
import llvm.GlobalVariable;
import llvm.IRType;
import llvm.ReturnType;
import llvm.Value;
import llvm.ValueType;
import llvm.constant.Constant;
import llvm.constant.ConstantInt;
import llvm.instr.AllocaInstr;
import llvm.instr.GepInstr;
import llvm.instr.Instruction;
import llvm.instr.StoreInstr;

import java.util.ArrayList;

public class VisitorDecl {
    private Visitor visitor;

    public VisitorDecl(Visitor visitor) {
        this.visitor = visitor;
    }

    public void visit(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        if (children.get(0).isType("ConstDecl")) visitConstDecl(children.get(0));
        else if (children.get(0).isType("VarDecl")) {
            visitVarDecl(children.get(0));
        }
    }

    public void visitVarDecl(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        String con = "var";
        if (children.get(0).isType("STATICTK")) {
            con = "static";
            String btype = visitBType(children.get(1));
            for (int i = 2; i < children.size(); i++) {
                if (children.get(i).isType("VarDef")) {
                    visitVarDef(children.get(i), con, btype, true);
                }
            }
        }
        else {
            String btype = visitBType(children.get(0));
            for (int i = 1; i < children.size(); i++) {
                if (children.get(i).isType("VarDef")) {
                    visitVarDef(children.get(i), con, btype, false);
                }
            }
        }
    }

    public void visitVarDef(ASTNode node, String con, String btype, boolean isStatic) {
        ArrayList<ASTNode> children = node.getChildren();
        String type = "var";
        if (children.size() > 2 && children.get(1).isType("LBRACK")) {
            type = "array";
        }

        int size = -1;
        if (type.equals("array")) {
            Value len = visitConstExp(children.get(2));
            size = Integer.valueOf(len.getName());
        }

        Value value;
        if (visitor.pt.getId() == 1 || isStatic) {
            IRType vType = new IRType("i32");
            if (type.equals("array")) {
                vType = new IRType(size);
            }
            if (visitor.pt.getId() == 1) {
                value = new GlobalVariable(children.get(0).getValue(), new IRType("ptr",vType));
            }
            else {
                value = new GlobalVariable(children.get(0).getValue()+"."+visitor.pt.getId(), new IRType("ptr",vType));
            }
            Builder.addGlobalValue((GlobalVariable) value);
        }
        else {
            if (type.equals("array")) {
                value = new AllocaInstr(new IRType(size));
            }
            else {
                value = new AllocaInstr(new IRType("i32"));
            }
            //Builder.addInstr((AllocaInstr) value);
        }
        visitor.addSymbol(children.get(0).getToken().getLine(), children.get(0).getValue(), type, btype, con, value);

        boolean initFlag = false;
        for (ASTNode child : children) {
            if (child.isType("InitVal")) {
                initFlag = true;
                if (visitor.pt.getId() == 1 || isStatic) {
                    if (type.equals("array")) {
                        ArrayList<Value> values = arrayInit(child, size, true);
                        ((GlobalVariable) value).setValue(values);
                    }
                    else {
                        ((GlobalValue) value).setValue(visitInitVal(child).getValue());
                    }
                }
                else {
                    if (type.equals("array")) {
                        ArrayList<Value> values = arrayInit(child, size,false);
                        for (int i=0;i<size;i++) {
                            GepInstr gepInstr = new GepInstr(value, new ConstantInt(i));
                            //Builder.addInstr(new StoreInstr(values.get(i), gepInstr));
                            new StoreInstr(values.get(i), gepInstr);
                            ((AllocaInstr) value).addValue(values.get(i));
                        }
                    }
                    else {
                        Value in = visitInitVal(child);
                        //Builder.addInstr(new StoreInstr(in, value));
                        new StoreInstr(in, value);
                        ((AllocaInstr) value).addValue(in);
                    }
                }
            }
        }

        if (!initFlag && visitor.pt.getId() != 1 && !type.equals("array") && !isStatic) {
            //Builder.addInstr(new StoreInstr(new ConstantInt(0), value));
            new StoreInstr(new ConstantInt(0), value);
            ((AllocaInstr) value).addValue(value);
        }
        else if (!initFlag && (visitor.pt.getId() == 1 || isStatic)) {
            if (type.equals("array")) {
                ArrayList<Value> values = new ArrayList<>();
                for (int i=0;i<size;i++) {
                    values.add(new ConstantInt(0));
                }
                ((GlobalVariable) value).setValue(values);
            }
            else {
                ((GlobalValue) value).setValue(new ConstantInt(0));
            }
        }
    }

    public Value visitInitVal(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        VisitorExp visitorExp = new VisitorExp(visitor);
        if (children.get(0).isType("Exp")) {
            return visitorExp.visit(children.get(0));
        }
        for (ASTNode child : children) {
            if (child.isType("Exp")) {
                visitorExp.visit(child);
            }
        }
        return null;
    }

    public void visitConstDecl(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        String con = "const";
        String btype = visitBType(children.get(1));
        for (int i = 2; i < children.size(); i++) {
            if (children.get(i).getName().equals("ConstDef")) {
                visitConstDef(children.get(i), con, btype);
            }
        }
    }

    public String visitBType(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        return children.get(0).getValue();
    }

    public void visitConstDef(ASTNode node, String con, String btype) {
        ArrayList<ASTNode> children = node.getChildren();
        String type = "var";
        if (children.get(1).isType("LBRACK")) type = "array";
        int size = -1;
        if (type.equals("array")) {
            Value len = visitConstExp(children.get(2));
            size = Integer.valueOf(len.getValue().getName());
        }
        Value value;
        if (visitor.pt.getId() == 1) {
            IRType ptType = new IRType("i32");
            if (type.equals("array")) {
                ptType = new IRType(size);
            }
            value = new GlobalVariable(children.get(0).getValue(), new IRType("ptr",ptType));
            Builder.addGlobalValue((GlobalValue) value);
        }
        else {
            if (type.equals("array")) {
                value = new AllocaInstr(new IRType(size));
            }
            else {
                value = new AllocaInstr(new IRType("i32"));
            }
            //Builder.addInstr((AllocaInstr) value);
        }
        visitor.addSymbol(children.get(0).getToken().getLine(), children.get(0).getValue(), type, btype, con, value);

        for (ASTNode child : children) {
            if (child.isType("ConstInitVal")) {
                if (visitor.pt.getId() == 1) {
                    if (type.equals("array")) {
                        ArrayList<Value> values = arrayInit(child, size, true);
                        ((GlobalVariable) value).setValue(values);
                    }
                    else {
                        ((GlobalValue) value).setValue(visitConstInitVal(child).getValue());
                    }
                }
                else {
                    if (type.equals("array")) {
                        ArrayList<Value> values = arrayInit(child, size,false);
                        for (int i=0;i<size;i++) {
                            GepInstr gepInstr = new GepInstr(value, new ConstantInt(i));
                            //Builder.addInstr(new StoreInstr(values.get(i), gepInstr));
                            new StoreInstr(values.get(i), gepInstr);
                            ((AllocaInstr) value).addValue(values.get(i));
                        }
                    }
                    else {
                        Value in = visitConstInitVal(child);
                        //Builder.addInstr(new StoreInstr(in, value));
                        new StoreInstr(in, value);
                        ((AllocaInstr) value).addValue(in);
                    }
                }
            }
        }
    }

    public ArrayList<Value> arrayInit(ASTNode node, int size, boolean global) {
        ArrayList<ASTNode> children = node.getChildren();
        ArrayList<Value> ans = new ArrayList<>();
        VisitorExp visitorExp = new VisitorExp(visitor);
        for (ASTNode child : children) {
            if (child.isType("ConstExp")) {
                ans.add(visitConstExp(child));
            }
            else if (child.isType("Exp")) {
                if (global) {
                    ans.add(visitorExp.visit(child).getValue());
                }
                else {
                    ans.add(visitorExp.visit(child));
                }

            }
        }
        while (ans.size() < size) {
            ans.add(new ConstantInt(0));
        }
        return ans;
    }

    public Value visitConstExp(ASTNode node) {
        VisitorExp visitorExp = new VisitorExp(visitor);
        return visitorExp.visitAddExp(node.getChildren().get(0)).getValue();
    }

    public Value visitConstInitVal(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        if (children.get(0).isType("ConstExp")) {
            return visitConstExp(children.get(0));
        }
        for (ASTNode child : children) {
            if (child.isType("ConstExp")) {
                visitConstExp(child);
            }
        }
        return null;
    }
}
