package frontend;

import util.Tool;

import java.io.FileNotFoundException;
import java.util.ArrayList;

public class Parser {
    private ArrayList<Token> tokens;
    private ASTNode root = null;
    private int pt = 0;
    private Tool tool = new Tool();

    public Parser(ArrayList<Token> tokens) {
        this.tokens = tokens;
    }

    public void analyse() throws FileNotFoundException {
        root = new ASTNode("CompUnit");
        while(!tokens.get(pt + 2).isType("LPARENT")) {
            ASTNode decl = new ASTNode("Decl");
            root.addChild(decl);
            Decl(decl);
        }
        while(!tokens.get(pt + 1).isType("MAINTK")) {
            ASTNode funcDef = new ASTNode("FuncDef");
            root.addChild(funcDef);
            FuncDef(funcDef);
        }
        ASTNode main = new ASTNode("MainFuncDef");
        root.addChild(main);
        main.addChild(getToken());
        main.addChild(getToken());
        main.addChild(getToken());
        main.addChild(getToken());
        ASTNode block = new ASTNode("Block");
        main.addChild(block);
        Block(block);

        tool.setOutput("parser.txt");
        root.printASTNode();
    }

    private void FuncDef(ASTNode FuncDef) {
        ASTNode FuncType = new ASTNode("FuncType");
        FuncDef.addChild(FuncType);
        FuncType(FuncType);
        FuncDef.addChild(getToken());
        FuncDef.addChild(getToken());
        if (!tokens.get(pt).isType("RPARENT")) {
            ASTNode FuncFParams = new ASTNode("FuncFParams");
            FuncDef.addChild(FuncFParams);
            FuncFParams(FuncFParams);
        }
        FuncDef.addChild(getToken());
        ASTNode Block = new ASTNode("Block");
        FuncDef.addChild(Block);
        Block(Block);
    }

    private void FuncFParams(ASTNode FuncFParams) {
        ASTNode FuncFParam = new ASTNode("FuncFParam");
        FuncFParams.addChild(FuncFParam);
        FuncFParam(FuncFParam);
        while (tokens.get(pt).isType("COMMA")) {
            FuncFParams.addChild(getToken());
            ASTNode FuncFParam1 = new ASTNode("FuncFParam");
            FuncFParams.addChild(FuncFParam1);
            FuncFParam(FuncFParam1);
        }
    }

    private void FuncFParam(ASTNode FuncFParam) {
        ASTNode BType = new ASTNode("BType");
        FuncFParam.addChild(BType);
        BType(BType);
        FuncFParam.addChild(getToken());
        if (tokens.get(pt).isType("LBRACK")) {
            FuncFParam.addChild(getToken());
            FuncFParam.addChild(getToken());
        }
    }

    private void FuncType(ASTNode FuncType) {
        FuncType.addChild(getToken());
    }

    private void Block(ASTNode block) {
        block.addChild(getToken());
        while(!tokens.get(pt).isType("RBRACE")) {
            ASTNode BlockItem = new ASTNode("BlockItem");
            block.addChild(BlockItem);
            BlockItem(BlockItem);
        }
        block.addChild(getToken());
    }

    private void BlockItem(ASTNode BlockItem) {
        if ((tokens.get(pt).isType("INTTK") || tokens.get(pt+1).isType("INTTK"))&&!tokens.get(pt).isType("LBRACE")&&!tokens.get(pt).isType("SEMICN")) {
            ASTNode decl = new ASTNode("Decl");
            BlockItem.addChild(decl);
            Decl(decl);
        }
        else {
            ASTNode stmt = new ASTNode("Stmt");
            BlockItem.addChild(stmt);
            Stmt(stmt);
        }
    }

    private void Stmt(ASTNode stmt) {
        if (tokens.get(pt).isType("BREAKTK")) {
            stmt.addChild(getToken());
            stmt.addChild(getToken());
        }
        else if (tokens.get(pt).isType("CONTINUETK")) {
            stmt.addChild(getToken());
            stmt.addChild(getToken());
        }
        else if (tokens.get(pt).isType("RETURNTK")) {
            stmt.addChild(getToken());
            if (!tokens.get(pt).isType("SEMICN")) {
                ASTNode Exp = new ASTNode("Exp");
                stmt.addChild(Exp);
                Exp(Exp);
            }
            stmt.addChild(getToken());
        }
        else if (tokens.get(pt).isType("PRINTFTK")) {
            stmt.addChild(getToken());
            stmt.addChild(getToken());
            stmt.addChild(getToken());
            while (tokens.get(pt).isType("COMMA")) {
                stmt.addChild(getToken());
                ASTNode Exp = new ASTNode("Exp");
                stmt.addChild(Exp);
                Exp(Exp);
            }
            stmt.addChild(getToken());
            stmt.addChild(getToken());
        }
        else if (tokens.get(pt).isType("IFTK")) {
            stmt.addChild(getToken());
            stmt.addChild(getToken());
            ASTNode Cond = new ASTNode("Cond");
            stmt.addChild(Cond);
            Cond(Cond);
            stmt.addChild(getToken());
            ASTNode stmt1 = new ASTNode("Stmt");
            stmt.addChild(stmt1);
            Stmt(stmt1);
            if (tokens.get(pt).isType("ELSETK")) {
                stmt.addChild(getToken());
                ASTNode stmt2 = new ASTNode("Stmt");
                stmt.addChild(stmt2);
                Stmt(stmt2);
            }
        }
        else if (tokens.get(pt).isType("FORTK")) {
            stmt.addChild(getToken());
            stmt.addChild(getToken());
            if (!tokens.get(pt).isType("SEMICN")) {
                ASTNode ForStmt = new ASTNode("ForStmt");
                stmt.addChild(ForStmt);
                ForStmt(ForStmt);
            }
            stmt.addChild(getToken());
            if (!tokens.get(pt).isType("SEMICN")) {
                ASTNode Cond = new ASTNode("Cond");
                stmt.addChild(Cond);
                Cond(Cond);
            }
            stmt.addChild(getToken());
            if (!tokens.get(pt).isType("RPARENT")) {
                ASTNode ForStmt = new ASTNode("ForStmt");
                stmt.addChild(ForStmt);
                ForStmt(ForStmt);
            }
            stmt.addChild(getToken());
            ASTNode Stmt1 = new ASTNode("Stmt");
            stmt.addChild(Stmt1);
            Stmt(Stmt1);
        }
        else if (tokens.get(pt).isType("SEMICN")) {
            stmt.addChild(getToken());
        }
        else if (tokens.get(pt).isType("LBRACE")) {
            ASTNode Block = new ASTNode("Block");
            stmt.addChild(Block);
            Block(Block);
        }
        else {
            int flag = 0; // 1 is a = exp , 0 is exp;
            for(int i=0;i<tokens.size();i++) {
                if (tokens.get(pt+i).isType("SEMICN")) {
                    break;
                }
                else if (tokens.get(pt+i).isType("ASSIGN")) {
                    flag = 1;
                    break;
                }
            }
            if(flag==1) {
                ASTNode LVal = new ASTNode("LVal");
                stmt.addChild(LVal);
                LVal(LVal);
                stmt.addChild(getToken());
            }
            ASTNode Exp = new ASTNode("Exp");
            stmt.addChild(Exp);
            Exp(Exp);
            stmt.addChild(getToken());
        }
    }

    private void ForStmt(ASTNode ForStmt) {
        ASTNode LVal = new ASTNode("LVal");
        ForStmt.addChild(LVal);
        LVal(LVal);
        ForStmt.addChild(getToken());
        ASTNode Exp = new ASTNode("Exp");
        ForStmt.addChild(Exp);
        Exp(Exp);
        while (tokens.get(pt).isType("COMMA")) {
            ForStmt.addChild(getToken());
            ASTNode LVal1 = new ASTNode("LVal");
            ForStmt.addChild(LVal1);
            LVal(LVal1);
            ForStmt.addChild(getToken());
            ASTNode Exp1 = new ASTNode("Exp");
            ForStmt.addChild(Exp1);
            Exp(Exp1);
        }
    }

    private void Cond(ASTNode Cond) {
        ASTNode LOrExp = new ASTNode("LOrExp");
        Cond.addChild(LOrExp);
        LOrExp(LOrExp);
    }

    private void LOrExp(ASTNode LOrExp) {
        ASTNode LAndExp = new ASTNode("LAndExp");
        LOrExp.addChild(LAndExp);
        LAndExp(LAndExp);
        while (tokens.get(pt).isType("OR")) {
            leftRecursion(LOrExp);
            LOrExp.addChild(getToken());
            ASTNode LAndExp1 = new ASTNode("LAndExp");
            LOrExp.addChild(LAndExp1);
            LAndExp(LAndExp1);
        }
    }

    private void LAndExp(ASTNode LAndExp) {
        ASTNode EqExp = new ASTNode("EqExp");
        LAndExp.addChild(EqExp);
        EqExp(EqExp);
        while (tokens.get(pt).isType("AND")) {
            leftRecursion(LAndExp);
            LAndExp.addChild(getToken());
            ASTNode EqExp1 = new ASTNode("EqExp");
            LAndExp.addChild(EqExp1);
            EqExp(EqExp1);
        }
    }

    private void EqExp(ASTNode EqExp) {
        ASTNode RelExp = new ASTNode("RelExp");
        EqExp.addChild(RelExp);
        RelExp(RelExp);
        while (tokens.get(pt).isType("EQL") || tokens.get(pt).isType("NEQ")) {
            leftRecursion(EqExp);
            EqExp.addChild(getToken());
            ASTNode RelExp1 = new ASTNode("RelExp");
            EqExp.addChild(RelExp1);
            RelExp(RelExp1);
        }
    }

    private void RelExp(ASTNode RelExp) {
        ASTNode AddExp = new ASTNode("AddExp");
        RelExp.addChild(AddExp);
        AddExp(AddExp);
        while (tokens.get(pt).isType("LSS") || tokens.get(pt).isType("LEQ") || tokens.get(pt).isType("GEQ") || tokens.get(pt).isType("GRE")) {
            leftRecursion(RelExp);
            RelExp.addChild(getToken());
            ASTNode AddExp1 = new ASTNode("AddExp");
            RelExp.addChild(AddExp1);
            AddExp(AddExp1);
        }
    }

    private void LVal(ASTNode LVal) {
        LVal.addChild(getToken());
        if (tokens.get(pt).isType("LBRACK")) {
            LVal.addChild(getToken());
            ASTNode Exp = new ASTNode("Exp");
            LVal.addChild(Exp);
            Exp(Exp);
            LVal.addChild(getToken());
        }
    }

    private void Decl(ASTNode decl) {
        if (tokens.get(pt).isType("CONSTTK")) {
            ASTNode constDecl = new ASTNode("ConstDecl");
            decl.addChild(constDecl);
            ConstDecl(constDecl);
        }
        else {
            ASTNode varDecl = new ASTNode("VarDecl");
            decl.addChild(varDecl);
            VarDecl(varDecl);
        }
    }

    private void VarDecl(ASTNode VarDecl) {
        if (tokens.get(pt).isType("STATICTK")) {
            VarDecl.addChild(getToken());
        }
        ASTNode BType = new ASTNode("BType");
        VarDecl.addChild(BType);
        BType(BType);
        ASTNode varDef = new ASTNode("VarDef");
        VarDecl.addChild(varDef);
        VarDef(varDef);
        while (tokens.get(pt).isType("COMMA")) {
            VarDecl.addChild(getToken());
            ASTNode varDef1 = new ASTNode("VarDef");
            VarDecl.addChild(varDef1);
            VarDef(varDef1);
        }
        VarDecl.addChild(getToken());
    }

    public void VarDef(ASTNode VarDef) {
        VarDef.addChild(getToken());
        if (tokens.get(pt).isType("LBRACK")) {
            VarDef.addChild(getToken());
            ASTNode constExp = new ASTNode("ConstExp");
            VarDef.addChild(constExp);
            ConstExp(constExp);
            VarDef.addChild(getToken());
        }
        if (tokens.get(pt).isType("ASSIGN")) {
            VarDef.addChild(getToken());
            ASTNode InitVal = new ASTNode("InitVal");
            VarDef.addChild(InitVal);
            InitVal(InitVal);
        }
    }

    public void InitVal(ASTNode InitVal) {
        if (tokens.get(pt).isType("LBRACE")) {
            InitVal.addChild(getToken());
            if (!tokens.get(pt).isType("RBRACE")) {
                ASTNode Exp = new ASTNode("Exp");
                InitVal.addChild(Exp);
                Exp(Exp);
                while (tokens.get(pt).isType("COMMA")) {
                    InitVal.addChild(getToken());
                    ASTNode Exp1 = new ASTNode("Exp");
                    InitVal.addChild(Exp1);
                    Exp(Exp1);
                }
            }
            InitVal.addChild(getToken());
        }
        else {
            ASTNode Exp = new ASTNode("Exp");
            InitVal.addChild(Exp);
            Exp(Exp);
        }
    }

    public void ConstDecl(ASTNode constDecl) {
        constDecl.addChild(getToken());
        ASTNode bType = new ASTNode("BType");
        constDecl.addChild(bType);
        BType(bType);
        ASTNode constDef = new ASTNode("ConstDef");
        constDecl.addChild(constDef);
        ConstDef(constDef);
        while (tokens.get(pt).isType("COMMA")) {
            constDecl.addChild(getToken());
            ASTNode constDef1 = new ASTNode("ConstDef");
            constDecl.addChild(constDef1);
            ConstDef(constDef1);
        }
        constDecl.addChild(getToken());
    }

    public void ConstDef(ASTNode constDef) {
        constDef.addChild(getToken());
        if (tokens.get(pt).isType("LBRACK")) {
            constDef.addChild(getToken());
            ASTNode constExp = new ASTNode("ConstExp");
            constDef.addChild(constExp);
            ConstExp(constExp);
            constDef.addChild(getToken());
        }
        constDef.addChild(getToken());
        ASTNode constInitVal = new ASTNode("ConstInitVal");
        constDef.addChild(constInitVal);
        ConstInitVal(constInitVal);
    }

    public void ConstInitVal(ASTNode constInitVal) {
        if (tokens.get(pt).isType("LBRACE")) {
            constInitVal.addChild(getToken());
            if (!tokens.get(pt).isType("RBRACE")) {
                ASTNode ConstExp = new ASTNode("ConstExp");
                constInitVal.addChild(ConstExp);
                ConstExp(ConstExp);
                while (tokens.get(pt).isType("COMMA")) {
                    constInitVal.addChild(getToken());
                    ASTNode ConstExp1 = new ASTNode("ConstExp");
                    constInitVal.addChild(ConstExp1);
                    ConstExp(ConstExp1);
                }
            }
            constInitVal.addChild(getToken());
        }
        else {
            ASTNode ConstExp = new ASTNode("ConstExp");
            constInitVal.addChild(ConstExp);
            ConstExp(ConstExp);
        }
    }

    public void ConstExp(ASTNode constExp) {
        ASTNode AddExp = new ASTNode("AddExp");
        constExp.addChild(AddExp);
        AddExp(AddExp);
    }

    public void Exp(ASTNode Exp) {
        ASTNode AddExp = new ASTNode("AddExp");
        Exp.addChild(AddExp);
        AddExp(AddExp);
    }

    public void AddExp(ASTNode AddExp) {
        ASTNode MulExp = new ASTNode("MulExp");
        AddExp.addChild(MulExp);
        MulExp(MulExp);
        while (tokens.get(pt).isType("PLUS") || tokens.get(pt).isType("MINU")) {
            leftRecursion(AddExp);
            AddExp.addChild(getToken());
            ASTNode MulExp1 = new ASTNode("MulExp");
            AddExp.addChild(MulExp1);
            MulExp(MulExp1);
        }
    }

    public void MulExp(ASTNode MulExp) {
        ASTNode UnaryExp = new ASTNode("UnaryExp");
        MulExp.addChild(UnaryExp);
        UnaryExp(UnaryExp);
        while (tokens.get(pt).isType("MULT") || tokens.get(pt).isType("DIV") || tokens.get(pt).isType("MOD")) {
            leftRecursion(MulExp);
            MulExp.addChild(getToken());
            ASTNode UnaryExp1 = new ASTNode("UnaryExp");
            MulExp.addChild(UnaryExp1);
            UnaryExp(UnaryExp1);
        }
    }

    public void UnaryExp(ASTNode UnaryExp) {
        if (tokens.get(pt).isType("PLUS") || tokens.get(pt).isType("MINU") || tokens.get(pt).isType("NOT")) {
            ASTNode UnaryOp = new ASTNode("UnaryOp");
            UnaryExp.addChild(UnaryOp);
            UnaryOp(UnaryOp);
            ASTNode UnaryExp1 = new ASTNode("UnaryExp");
            UnaryExp.addChild(UnaryExp1);
            UnaryExp(UnaryExp1);
        }
        else if (tokens.get(pt+1).isType("LPARENT")) {
            UnaryExp.addChild(getToken());
            UnaryExp.addChild(getToken());
            if (!tokens.get(pt).isType("RPARENT")) {
                ASTNode FuncRParams = new ASTNode("FuncRParams");
                UnaryExp.addChild(FuncRParams);
                FuncRParams(FuncRParams);
            }
            UnaryExp.addChild(getToken());
        }
        else {
            ASTNode PrimaryExp = new ASTNode("PrimaryExp");
            UnaryExp.addChild(PrimaryExp);
            PrimaryExp(PrimaryExp);
        }
    }

    public void PrimaryExp(ASTNode PrimaryExp) {
        if (tokens.get(pt).isType("LPARENT")) {
            PrimaryExp.addChild(getToken());
            ASTNode Exp = new ASTNode("Exp");
            PrimaryExp.addChild(Exp);
            Exp(Exp);
            PrimaryExp.addChild(getToken());
        }
        else if (tokens.get(pt).isType("INTCON")) {
            ASTNode Number = new ASTNode("Number");
            PrimaryExp.addChild(Number);
            Number(Number);
        }
        else {
            ASTNode LVal = new ASTNode("LVal");
            PrimaryExp.addChild(LVal);
            LVal(LVal);
        }
    }

    public void Number(ASTNode Number) {
        Number.addChild(getToken());
    }

    public void FuncRParams(ASTNode FuncRParams) {
        ASTNode Exp = new ASTNode("Exp");
        FuncRParams.addChild(Exp);
        Exp(Exp);
        while(tokens.get(pt).isType("COMMA")) {
            FuncRParams.addChild(getToken());
            ASTNode Exp1 = new ASTNode("Exp");
            FuncRParams.addChild(Exp1);
            Exp(Exp1);
        }
    }

    public void UnaryOp(ASTNode UnaryOp) {
        UnaryOp.addChild(getToken());
    }

    public void BType(ASTNode bType) {
        bType.addChild(getToken());
    }

    private ASTNode getToken() {
        ASTNode token = new ASTNode(tokens.get(pt));
        pt++;
        return token;
    }

    private void leftRecursion(ASTNode parent) {
        ASTNode cloned = parent.clone();
        parent.clearChild();
        parent.addChild(cloned);
    }

}
