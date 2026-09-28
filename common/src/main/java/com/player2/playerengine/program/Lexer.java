package com.player2.playerengine.program;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Tokens of the program subset (§6.1). Syntax outside the subset is named here when it is lexical. */
final class Lexer {
    enum Kind { IDENT, KEYWORD, NUMBER, STRING, PUNCT, EOF }

    record Token(Kind kind, String text, double number, int line, int col) {
        boolean is(String s) {
            return (kind == Kind.PUNCT || kind == Kind.KEYWORD) && text.equals(s);
        }
    }

    static final Set<String> KEYWORDS = Set.of("let", "const", "var", "if", "else", "for", "of", "in", "while",
            "do", "function", "return", "break", "continue", "try", "catch", "finally", "throw", "true", "false",
            "null", "undefined", "new", "this", "class", "async", "await", "yield", "import", "export", "switch",
            "case", "default", "typeof", "instanceof", "delete", "void", "with", "debugger", "super");

    /** Longest first, so {@code ===} wins over {@code ==}. */
    private static final String[] PUNCT = {"===", "!==", "...", "**=", "&&=", "||=", "??=",
            "==", "!=", "<=", ">=", "&&", "||", "??", "?.", "++", "--", "+=", "-=", "*=", "/=", "%=", "=>", "**",
            "{", "}", "(", ")", "[", "]", ";", ",", ".", "<", ">", "+", "-", "*", "/", "%", "!", "=", "?", ":",
            "&", "|", "^", "~"};

    private final String src;
    private int pos;
    private int line = 1;
    private int col = 1;

    private Lexer(String src) {
        this.src = src;
    }

    static List<Token> lex(String src) {
        Lexer l = new Lexer(src);
        List<Token> out = new ArrayList<>();
        Token t;
        do {
            t = l.next();
            out.add(t);
        } while (t.kind != Kind.EOF);
        return out;
    }

    private ProgramError.Failure fail(int ln, int cl, String msg) {
        return new ProgramError.Failure(ProgramError.bad(ln, cl, msg));
    }

    private char peek(int ahead) {
        int i = pos + ahead;
        return i < src.length() ? src.charAt(i) : '\0';
    }

    private void advance() {
        if (src.charAt(pos) == '\n') {
            line++;
            col = 1;
        } else {
            col++;
        }
        pos++;
    }

    private Token next() {
        skipSpaceAndComments();
        int ln = line;
        int cl = col;
        if (pos >= src.length()) {
            return new Token(Kind.EOF, "<end>", 0, ln, cl);
        }
        char c = src.charAt(pos);
        if (Character.isJavaIdentifierStart(c) && c != '\\') {
            int start = pos;
            while (pos < src.length() && Character.isJavaIdentifierPart(src.charAt(pos))) {
                advance();
            }
            String word = src.substring(start, pos);
            return new Token(KEYWORDS.contains(word) ? Kind.KEYWORD : Kind.IDENT, word, 0, ln, cl);
        }
        if (Character.isDigit(c) || (c == '.' && Character.isDigit(peek(1)))) {
            return number(ln, cl);
        }
        if (c == '"' || c == '\'') {
            return string(c, ln, cl);
        }
        if (c == '`') {
            throw fail(ln, cl, "template literals are not supported; write it as \"text \" + value");
        }
        for (String p : PUNCT) {
            if (src.startsWith(p, pos)) {
                for (int i = 0; i < p.length(); i++) {
                    advance();
                }
                return new Token(Kind.PUNCT, p, 0, ln, cl);
            }
        }
        throw fail(ln, cl, "unexpected character '" + c + "'");
    }

    private void skipSpaceAndComments() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (Character.isWhitespace(c)) {
                advance();
            } else if (c == '/' && peek(1) == '/') {
                while (pos < src.length() && src.charAt(pos) != '\n') {
                    advance();
                }
            } else if (c == '/' && peek(1) == '*') {
                int ln = line;
                int cl = col;
                advance();
                advance();
                while (pos < src.length() && !(src.charAt(pos) == '*' && peek(1) == '/')) {
                    advance();
                }
                if (pos >= src.length()) {
                    throw fail(ln, cl, "unterminated comment");
                }
                advance();
                advance();
            } else {
                return;
            }
        }
    }

    private Token number(int ln, int cl) {
        int start = pos;
        if (src.charAt(pos) == '0' && (peek(1) == 'x' || peek(1) == 'X' || peek(1) == 'b' || peek(1) == 'o')) {
            throw fail(ln, cl, "only decimal numbers are supported");
        }
        while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.'
                || src.charAt(pos) == '_')) {
            advance();
        }
        if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
            advance();
            if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                advance();
            }
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                advance();
            }
        }
        String text = src.substring(start, pos);
        try {
            return new Token(Kind.NUMBER, text, Double.parseDouble(text.replace("_", "")), ln, cl);
        } catch (NumberFormatException e) {
            throw fail(ln, cl, "not a number: " + text);
        }
    }

    private Token string(char quote, int ln, int cl) {
        advance();
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length() || src.charAt(pos) == '\n') {
                throw fail(ln, cl, "unterminated string");
            }
            char c = src.charAt(pos);
            if (c == quote) {
                advance();
                return new Token(Kind.STRING, sb.toString(), 0, ln, cl);
            }
            if (c == '\\') {
                advance();
                if (pos >= src.length()) {
                    throw fail(ln, cl, "unterminated string");
                }
                char e = src.charAt(pos);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case '\\', '\'', '"' -> sb.append(e);
                    default -> throw fail(line, col, "unsupported escape \\" + e);
                }
                advance();
                continue;
            }
            sb.append(c);
            advance();
        }
    }
}
