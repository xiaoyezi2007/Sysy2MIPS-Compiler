package frontend;

import llvm.*;
import llvm.constant.ConstantInt;
import llvm.constant.ConstantString;
import llvm.constant.ConstantVoid;
import llvm.instr.*;

import java.util.ArrayList;

public class VisitorStmt {
    private final Visitor visitor;

    public VisitorStmt(Visitor visitor) {
        this.visitor = visitor;
    }

    public void visit(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        if (!children.isEmpty() && children.get(0).isType("PRINTFTK")) {
            visitPrintf(node);
        }
        else if (!children.isEmpty() && children.get(0).isType("RETURNTK")) {
            visitReturn(node);
        }
        else if (!children.isEmpty() && children.get(0).isType("BREAKTK") || children.get(0).isType("CONTINUETK")) {
            visitBreakContinue(node);
        }
        else if (!children.isEmpty() && children.get(0).isType("Block")) {
            VisitorBlock visitorBlock = new VisitorBlock(visitor);
            visitor.beforeBlock();
            visitorBlock.visit(children.get(0));
            visitor.afterBlock(children.get(0));
        }
        else if (!children.isEmpty() && children.get(0).isType("LVal")) {
            visitAssign(node);
        }
        else if (!children.isEmpty() && children.get(0).isType("Exp")) {
            visitExpStmt(node);
        }
        else if (!children.isEmpty() && children.get(0).isType("IFTK")) {
            visitIfStmt(node);
        }
        else if (!children.isEmpty() && children.get(0).isType("FORTK")) {
            if (!children.isEmpty() && children.get(0).isType("FORTK")) visitor.cycle++;
            visitFor(node);
            if (!children.isEmpty() && children.get(0).isType("FORTK")) visitor.cycle--;
        }
    }

    public void visitFor(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        ArrayList<Integer> SEMICN = new ArrayList();
        for (int i = 0; i < children.size(); i++) {
            if (children.get(i).isType("SEMICN")) SEMICN.add(i);
        }
        if (children.get(SEMICN.get(0)-1).isType("ForStmt")) {
            visitForStmt(children.get(SEMICN.get(0)-1));
        }
        BasicBlock cycleBlock = new BasicBlock();
        BasicBlock forStmtBlock = new BasicBlock();
        BasicBlock endBlock = new BasicBlock();
        BasicBlock condBlock = new BasicBlock();

        Value condition = null;
        Builder.addForBlocks(cycleBlock, forStmtBlock, endBlock);
        //Builder.addInstr(new JumpInstr(condBlock));
        new JumpInstr(condBlock);

        Builder.addBasicBlock(condBlock);
        if (children.get(SEMICN.get(0)+1).isType("Cond")) {
            Value cond = visitCond(children.get(SEMICN.get(0)+1));
            if (cond instanceof CmpInstr) {
                condition = cond;
            }
            else {
                condition = new CmpInstr(cond,"!=",new ConstantInt(0));
            }
        }
        if (condition == null) {
            //Builder.addInstr(new JumpInstr(cycleBlock));
            new JumpInstr(cycleBlock);
        }
        else {
            //Builder.addInstr((Instruction) condition);
            //Builder.addInstr(new BranchInstr(condition, cycleBlock, endBlock));
            new BranchInstr(condition, cycleBlock, endBlock);
        }

        Builder.addBasicBlock(cycleBlock);
        visit(children.get(children.size()-1));
        //Builder.addInstr(new JumpInstr(forStmtBlock));
        new JumpInstr(forStmtBlock);

        Builder.addBasicBlock(forStmtBlock);
        if (children.get(SEMICN.get(1)+1).isType("ForStmt")) {
            visitForStmt(children.get(SEMICN.get(1)+1));
        }
        //Builder.addInstr(new JumpInstr(condBlock));
        new JumpInstr(condBlock);

        Builder.addBasicBlock(endBlock);

        Builder.popForBlocks();
    }

    public void visitForStmt(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        for (int i=0;i<children.size();i++) {
            ASTNode assign = new ASTNode("Stmt");
            assign.addChild(children.get(i));
            assign.addChild(children.get(i+1));
            assign.addChild(children.get(i+2));
            visitAssign(assign);
            i+=3;
        }
    }

    public void addIfBlock(BasicBlock ifBlock, BasicBlock elseBlock, BasicBlock endBlock) {
        Builder.ifBlock = ifBlock;
        Builder.elseBlock = elseBlock;
        Builder.endBlock = endBlock;
    }

    public void visitIfStmt(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        BasicBlock ifBlock = new BasicBlock();
        BasicBlock elseBlock = new BasicBlock();
        BasicBlock endBlock = new BasicBlock();
        if (children.size() == 5) {
            elseBlock = null;
        }
        addIfBlock(ifBlock, elseBlock, endBlock);
        Value cond = visitCond(children.get(2));
        Value condition = cond;
        if (cond instanceof CmpInstr) {
            //Builder.addInstr((Instruction) cond);
        }
        else {
            condition = new CmpInstr(cond,"!=",new ConstantInt(0));
            //Builder.addInstr((Instruction) condition);
        }
        if (children.size() == 5) {
            //Builder.addInstr(new BranchInstr(condition, ifBlock, endBlock));
            new BranchInstr(condition, ifBlock, endBlock);
            Builder.addBasicBlock(ifBlock);
            visit(children.get(4));
            //Builder.addInstr(new JumpInstr(endBlock));
            new JumpInstr(endBlock);
            Builder.addBasicBlock(endBlock);
        }
        else {
            //Builder.addInstr(new BranchInstr(condition, ifBlock, elseBlock));
            new BranchInstr(condition, ifBlock, elseBlock);
            Builder.addBasicBlock(ifBlock);
            visit(children.get(4));
            //Builder.addInstr(new JumpInstr(endBlock));
            new JumpInstr(endBlock);
            Builder.addBasicBlock(elseBlock);
            visit(children.get(6));
            //Builder.addInstr(new JumpInstr(endBlock));
            new JumpInstr(endBlock);
            Builder.addBasicBlock(endBlock);
        }
        addIfBlock(null,null,null);
    }

    public Value visitCond(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        VisitorExp visitorExp = new VisitorExp(visitor);
        return visitorExp.visitLOrExp(children.get(0));
    }

    public void visitExpStmt(ASTNode node) {
        VisitorExp visitorExp = new VisitorExp(visitor);
        //Builder.addInstr((Instruction) visitorExp.visit(node.getChildren().get(0)));
        visitorExp.visit(node.getChildren().get(0));
    }

    public void visitAssign(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        VisitorExp visitorExp = new VisitorExp(visitor);
        Value in = visitorExp.visit(children.get(2));
        if (visitor.checkLValc(children.get(0))) {
            if (visitor.checkLValh(children.get(0))) {
                Value to = visitor.visitLVal(children.get(0));
                //Builder.addInstr(new StoreInstr(in,to));
                new StoreInstr(in,to);
            }
        }
    }

    public void visitBreakContinue(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        if (visitor.cycle == 0) {
            visitor.error.addError("m", children.get(0).getToken().getLine());
            return;
        }
        if (children.get(0).isType("BREAKTK")) {
            //Builder.addInstr(new JumpInstr(Builder.getForBlock("end")));
            new JumpInstr(Builder.getForBlock("end"));
        }
        else if (children.get(0).isType("CONTINUETK")) {
            //Builder.addInstr(new JumpInstr(Builder.getForBlock("forStmt")));
            new JumpInstr(Builder.getForBlock("forStmt"));
        }
    }

    private void visitReturn(ASTNode node) {
        Value returnIR = null;
        ArrayList<ASTNode> children = node.getChildren();
        if (children.size() > 1 && !children.get(1).isType("SEMICN")) {
            if(!visitor.Func.returnInt()) visitor.error.addError("f", children.get(0).getToken().getLine());
        }
        if (children.size() > 1 && children.get(1).isType("Exp")) {
            VisitorExp visitorExp = new VisitorExp(visitor);
            returnIR = visitorExp.visit(children.get(1));
        }
        else {
            returnIR = new ConstantVoid();
        }
        RetInstr retInstr = new RetInstr(returnIR);
        //Builder.addInstr(retInstr);
    }

    private void visitPrintf(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        if (!checkPrintf(node)) {
            visitor.error.addError("l", children.get(0).getToken().getLine());
            return;
        }
        ArrayList<Value> values = new ArrayList<>();
        for (int i=0;i<children.size();i++) {
            if (children.get(i).isType("Exp")) {
                VisitorExp visitorExp = new VisitorExp(visitor);
                values.add(visitorExp.visit(children.get(i)));
            }
        }

        String strcon = children.get(2).getValue();
        String tmp = "";
        int len = 0;
        int cnt = 0;
        for (int i=1;i<strcon.length()-1;i++) {
            if (i<strcon.length()-2 && strcon.charAt(i)=='%' && strcon.charAt(i+1)=='d') {
                if (len > 0) {
                    GlobalString globalString = new GlobalString(new ConstantString(tmp), len);
                    tmp = "";
                    Builder.addGlobalValue(globalString);
                    ArrayList<Value> params = new ArrayList<>();
                    params.add(globalString);
                    //Builder.addInstr(new CallInstr(Builder.putstr, params));
                    new CallInstr(Builder.putstr, params);
                }

                ArrayList<Value> params1 = new ArrayList<>();
                params1.add(values.get(cnt));
                cnt++;
                //Builder.addInstr(new CallInstr(Builder.putint, params1));
                new CallInstr(Builder.putint, params1);

                i++;
                len = 0;
            }
            else if (i < strcon.length()-2 && strcon.charAt(i)=='\\' && strcon.charAt(i+1)=='n') {
                tmp += "\\0A";
                len += 1;
                i++;
            }
            else {
                tmp += strcon.charAt(i);
                len += 1;
            }
        }
        if (!tmp.isEmpty()) {
            GlobalString globalString = new GlobalString(new ConstantString(tmp), len);
            tmp = "";
            Builder.addGlobalValue(globalString);
            ArrayList<Value> params = new ArrayList<>();
            params.add(globalString);
            //Builder.addInstr(new CallInstr(Builder.putstr, params));
            new CallInstr(Builder.putstr, params);
        }
    }

    private boolean checkPrintf(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        String strcon = children.get(2).getValue();
        int cnt = 0;
        for (int i=0; i<strcon.length()-1; i++) {
            if (strcon.charAt(i) == '%' && strcon.charAt(i+1) == 'd') {
                cnt++;
            }
        }
        int cnt1 = 0;
        for (int i=3;i<children.size();i++) {
            if (children.get(i).isType("COMMA")) cnt1++;
        }
        return cnt == cnt1;
    }
}
