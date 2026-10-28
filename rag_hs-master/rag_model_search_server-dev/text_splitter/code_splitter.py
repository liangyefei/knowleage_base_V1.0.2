#!/usr/bin/env python3
# @Time : 2024/7/3 11:51:08
# @Author : Hetao
# @File : [Code] --> code_splitter.py
# @Software: PyCharm
# @Function: 函数级的代码分割器实现；
from __future__ import annotations

import re
from typing import Any

from langchain_text_splitters.base import Language, TextSplitter

_NCLASS_KEY = '\nclass '


def _split_text_with_regex(
    text: str, separator: str, keep_separator: bool
) -> list[str]:
    # Now that we have the separator, split the text
    if separator:
        if keep_separator:
            # The parentheses in the pattern keep the delimiters in the result.
            _splits = re.split(f"({separator})", text)
            splits = [_splits[i] + _splits[i + 1] for i in range(1, len(_splits), 2)]
            if len(_splits) % 2 == 0:
                splits += _splits[-1:]
            splits = [_splits[0]] + splits
        else:
            splits = re.split(separator, text)
    else:
        splits = list(text)
    return [s for s in splits if s != ""]


class RecursiveCodeTextSplitter(TextSplitter):
    """Splitting text by recursively look at characters.

    Recursively tries to split by different characters to find one
    that works.
    """

    def __init__(
        self,
        separators: list[str] | None = None,
        keep_separator: bool = True,
        is_separator_regex: bool = False,
        **kwargs: Any,
    ) -> None:
        """Create a new TextSplitter."""
        super().__init__(keep_separator=keep_separator, **kwargs)
        self._separators = separators
        self._is_separator_regex = is_separator_regex

    @staticmethod
    def get_separators_for_language(language: Language) -> list[str]:
        _NL_VAR = '\nvar '
        _NL_CONST = '\nconst '
        _NL_TYPE = '\ntype '
        _NL_IF = '\nif '
        _NL_FOR = '\nfor '
        _NL_SWITCH = '\nswitch '
        _NL_CASE = '\ncase '
        _NL_NL = '\n\n'
        _NL_WHILE = '\nwhile '
        _NL_FUNCTION = '\nfunction '
        _NL_ENUM = '\nenum '
        _NL_INTERFACE = '\ninterface '
        _NL_LET = '\nlet '
        _NL_DO = '\ndo '
        if language == Language.CPP:
            return [
                # ----------------------------------------------
                # # Split along class definitions
                # _NCLASS_KEY,
                # # Split along function definitions
                # "\nvoid ",
                # "\nint ",
                # "\nfloat ",
                # "\ndouble ",
                # # Split along control flow statements
                # "\nif ",
                # "\nfor ",
                # "\nwhile ",
                # "\nswitch ",
                # "\ncase ",
                # # Split by the normal type of lines
                # "\n\n",
                # "\n",
                # " ",
                # "",
                # ----------------------------------------------
                "int main",
                "static void ",
                "static bool ",
                "static std::",
                "inline void ",
                "inline double ",
                "inline int ",
                "inline static bool ",
                _NCLASS_KEY,
                "\n\nstatic ",
                "\n\ninline ",
                "\n\nint ",
                "\n\ndouble ",
                "\n\nbool ",
                "\nvoid ",
                "\n\nvoid ",
                "\n    void ",
                "\n\n    void ",
                # "\n\n    int ",
                # "\n\n    double ",
                # "\n\n    bool ",
                # "\n\nstruct ",
                r"\/\*\*",
                "\n\n\\/\\/",
                "\n\n\n",
                "\n__kernel void",
                # "",
                # "",
            ]
        elif language == Language.GO:
            return [
                # Split along function definitions
                "\nfunc ",
                _NL_VAR,
                _NL_CONST,
                _NL_TYPE,
                # Split along control flow statements
                _NL_IF,
                _NL_FOR,
                _NL_SWITCH,
                _NL_CASE,
                # Split by the normal type of lines
                _NL_NL,
                "\n",
                " ",
                "",
            ]
        elif language == Language.JAVA:
            return [
                # ----------------------------------------------
                # # Split along class definitions
                # _NCLASS_KEY,
                # # Split along method definitions
                # "\npublic ",
                # "\nprotected ",
                # "\nprivate ",
                # "\nstatic ",
                # # Split along control flow statements
                # "\nif ",
                # "\nfor ",
                # "\nwhile ",
                # "\nswitch ",
                # "\ncase ",
                # # Split by the normal type of lines
                # "\n\n",
                # "\n",
                # " ",
                # "",
                # ----------------------------------------------
                "\n/\\*\\*",
                _NCLASS_KEY,
                "\npublic class ",
                "\nprivate static class ",
                "\n\n\n    public ",
                "@Override\n  public ",
                "@Override\n\tpublic ",
                "@Override\n    public ",
                "\n    public void ",
                "\n    public static ",
                "\n    public interface ",
                "\n\n\n    private ",
                "\n    private void ",
                "\n    private boolean ",
                # " ",
            ]
        elif language == Language.KOTLIN:
            return [
                # Split along class definitions
                _NCLASS_KEY,
                # Split along method definitions
                "\npublic ",
                "\nprotected ",
                "\nprivate ",
                "\ninternal ",
                "\ncompanion ",
                "\nfun ",
                "\nval ",
                _NL_VAR,
                # Split along control flow statements
                _NL_IF,
                _NL_FOR,
                _NL_WHILE,
                "\nwhen ",
                _NL_CASE,
                "\nelse ",
                # Split by the normal type of lines
                _NL_NL,
                "\n",
                " ",
                "",
            ]
        elif language == Language.JS:
            return [
                # ----------------------------------------------
                # # Split along function definitions
                # "\nfunction ",
                # "\nconst ",
                # "\nlet ",
                # "\nvar ",
                # _NCLASS_KEY,
                # # Split along control flow statements
                # "\nif ",
                # "\nfor ",
                # "\nwhile ",
                # "\nswitch ",
                # "\ncase ",
                # "\ndefault ",
                # # Split by the normal type of lines
                # "\n\n",
                # "\n",
                # " ",
                # "",
                # ----------------------------------------------
                _NL_FUNCTION,
                _NCLASS_KEY,
                _NL_NL,
                # "",
            ]
        elif language == Language.TS:
            return [
                _NL_ENUM,
                _NL_INTERFACE,
                "\nnamespace ",
                _NL_TYPE,
                # Split along class definitions
                _NCLASS_KEY,
                # Split along function definitions
                _NL_FUNCTION,
                _NL_CONST,
                _NL_LET,
                _NL_VAR,
                # Split along control flow statements
                _NL_IF,
                _NL_FOR,
                _NL_WHILE,
                _NL_SWITCH,
                _NL_CASE,
                "\ndefault ",
                # Split by the normal type of lines
                _NL_NL,
                "\n",
                " ",
                "",
                "",
            ]
        elif language == Language.PHP:
            return [
                # ----------------------------------------------
                # # Split along function definitions
                # "\nfunction ",
                # # Split along class definitions
                # _NCLASS_KEY,
                # # Split along control flow statements
                # "\nif ",
                # "\nforeach ",
                # "\nwhile ",
                # "\ndo ",
                # "\nswitch ",
                # "\ncase ",
                # # Split by the normal type of lines
                # "\n\n",
                # "\n",
                # " ",
                # "",
                # ----------------------------------------------
                "/** ",
                _NCLASS_KEY,
                "abstract class ",
                _NL_FUNCTION,
                "interface IComparable",
                "public function ",
                "final public function ",
                "public static function ",
                "private function ",
                "private static function ",
                "protected function ",
                # " ",
            ]
        elif language == Language.PROTO:
            return [
                # Split along message definitions
                "\nmessage ",
                # Split along service definitions
                "\nservice ",
                # Split along enum definitions
                _NL_ENUM,
                # Split along option definitions
                "\noption ",
                # Split along import statements
                "\nimport ",
                # Split along syntax declarations
                "\nsyntax ",
                # Split by the normal type of lines
                _NL_NL,
                "\n",
                " ",
                "",
            ]
        elif language == Language.PYTHON:
            return [
                # ----------------------------------------------
                # # First, try to split along class definitions
                # _NCLASS_KEY,
                # "\ndef ",
                # "\n\tdef ",
                # # Now split by the normal type of lines
                # "\n\n",
                # "\n",
                # " ",
                # "",
                # ----------------------------------------------
                "class ",
                "def ",
                "if __name__",
            ]
        elif language == Language.RST:
            return [
                # Split along section titles
                "\n=+\n",
                "\n-+\n",
                "\n\\*+\n",
                # Split along directive markers
                "\n\n.. *\n\n",
                # Split by the normal type of lines
                _NL_NL,
                "\n",
                " ",
                "",
            ]
        elif language == Language.RUBY:
            return [
                # Split along method definitions
                "\ndef ",
                _NCLASS_KEY,
                # Split along control flow statements
                _NL_IF,
                "\nunless ",
                _NL_WHILE,
                _NL_FOR,
                _NL_DO,
                "\nbegin ",
                "\nrescue ",
                # Split by the normal type of lines
                _NL_NL,
                "\n",
                " ",
                "",
            ]
        elif language == Language.RUST:
            return [
                # Split along function definitions
                "\nfn ",
                _NL_CONST,
                _NL_LET,
                # Split along control flow statements
                _NL_IF,
                _NL_WHILE,
                _NL_FOR,
                "\nloop ",
                "\nmatch ",
                _NL_CONST,
                # Split by the normal type of lines
                _NL_NL,
                "\n",
                " ",
                "",
            ]
        elif language == Language.SCALA:
            return [
                # Split along class definitions
                _NCLASS_KEY,
                "\nobject ",
                # Split along method definitions
                "\ndef ",
                "\nval ",
                _NL_VAR,
                # Split along control flow statements
                _NL_IF,
                _NL_FOR,
                _NL_WHILE,
                "\nmatch ",
                _NL_CASE,
                # Split by the normal type of lines
                _NL_NL,
                "\n",
                " ",
                "",
            ]
        elif language == Language.SWIFT:
            return [
                # Split along function definitions
                "\nfunc ",
                # Split along class definitions
                _NCLASS_KEY,
                "\nstruct ",
                _NL_ENUM,
                # Split along control flow statements
                _NL_IF,
                _NL_FOR,
                _NL_WHILE,
                _NL_DO,
                _NL_SWITCH,
                _NL_CASE,
                # Split by the normal type of lines
                _NL_NL,
                "\n",
                " ",
                "",
            ]
        elif language == Language.MARKDOWN:
            return [
                # First, try to split along Markdown headings (starting with level 2)
                "\n#{1,6} ",
                # Note the alternative syntax for headings (below) is not handled here
                # Heading level 2
                # ---------------
                # End of code block
                "```\n",
                # Horizontal lines
                "\n\\*\\*\\*+\n",
                "\n---+\n",
                "\n___+\n",
                # Note that this splitter doesn't handle horizontal lines defined
                # by *three or more* of ***, ---, or ___, but this is not handled
                _NL_NL,
                "\n",
                " ",
                "",
            ]
        elif language == Language.LATEX:
            return [
                # First, try to split along Latex sections
                "\n\\\\chapter{",
                "\n\\\\section{",
                "\n\\\\subsection{",
                "\n\\\\subsubsection{",
                # Now split by environments
                "\n\\\\begin{enumerate}",
                "\n\\\\begin{itemize}",
                "\n\\\\begin{description}",
                "\n\\\\begin{list}",
                "\n\\\\begin{quote}",
                "\n\\\\begin{quotation}",
                "\n\\\\begin{verse}",
                "\n\\\\begin{verbatim}",
                # Now split by math environments
                "\n\\\begin{align}",
                "$$",
                "$",
                # Now split by the normal type of lines
                " ",
                "",
            ]
        elif language == Language.HTML:
            return [
                # First, try to split along HTML tags
                "<body",
                "<div",
                "<p",
                "<br",
                "<li",
                "<h1",
                "<h2",
                "<h3",
                "<h4",
                "<h5",
                "<h6",
                "<span",
                "<table",
                "<tr",
                "<td",
                "<th",
                "<ul",
                "<ol",
                "<header",
                "<footer",
                "<nav",
                # Head
                "<head",
                "<style",
                "<script",
                "<meta",
                "<title",
                "",
            ]
        elif language == Language.CSHARP:
            return [
                _NL_INTERFACE,
                _NL_ENUM,
                "\nimplements ",
                "\ndelegate ",
                "\nevent ",
                # Split along class definitions
                _NCLASS_KEY,
                "\nabstract ",
                # Split along method definitions
                "\npublic ",
                "\nprotected ",
                "\nprivate ",
                "\nstatic ",
                "\nreturn ",
                # Split along control flow statements
                _NL_IF,
                "\ncontinue ",
                _NL_FOR,
                "\nforeach ",
                _NL_WHILE,
                _NL_SWITCH,
                "\nbreak ",
                _NL_CASE,
                "\nelse ",
                # Split by exceptions
                "\ntry ",
                "\nthrow ",
                "\nfinally ",
                "\ncatch ",
                # Split by the normal type of lines
                _NL_NL,
                "\n",
                " ",
                "",
            ]
        elif language == Language.SOL:
            return [
                # Split along compiler information definitions
                "\npragma ",
                "\nusing ",
                # Split along contract definitions
                "\ncontract ",
                _NL_INTERFACE,
                "\nlibrary ",
                # Split along method definitions
                "\nconstructor ",
                _NL_TYPE,
                _NL_FUNCTION,
                "\nevent ",
                "\nmodifier ",
                "\nerror ",
                "\nstruct ",
                _NL_ENUM,
                # Split along control flow statements
                _NL_IF,
                _NL_FOR,
                _NL_WHILE,
                "\ndo while ",
                "\nassembly ",
                # Split by the normal type of lines
                _NL_NL,
                "\n",
                " ",
                "",
            ]
        elif language == Language.COBOL:
            return [
                # Split along divisions
                "\nIDENTIFICATION DIVISION.",
                "\nENVIRONMENT DIVISION.",
                "\nDATA DIVISION.",
                "\nPROCEDURE DIVISION.",
                # Split along sections within DATA DIVISION
                "\nWORKING-STORAGE SECTION.",
                "\nLINKAGE SECTION.",
                "\nFILE SECTION.",
                # Split along sections within PROCEDURE DIVISION
                "\nINPUT-OUTPUT SECTION.",
                # Split along paragraphs and common statements
                "\nOPEN ",
                "\nCLOSE ",
                "\nREAD ",
                "\nWRITE ",
                "\nIF ",
                "\nELSE ",
                "\nMOVE ",
                "\nPERFORM ",
                "\nUNTIL ",
                "\nVARYING ",
                "\nACCEPT ",
                "\nDISPLAY ",
                "\nSTOP RUN.",
                # Split by the normal type of lines
                "\n",
                " ",
                "",
            ]
        elif language == Language.LUA:
            return [
                # Split along variable and table definitions
                "\nlocal ",
                # Split along function definitions
                _NL_FUNCTION,
                # Split along control flow statements
                _NL_IF,
                _NL_FOR,
                _NL_WHILE,
                "\nrepeat ",
                # Split by the normal type of lines
                _NL_NL,
                "\n",
                " ",
                "",
            ]
        elif language == Language.HASKELL:
            return [
                # Split along function definitions
                "\nmain :: ",
                "\nmain = ",
                _NL_LET,
                "\nin ",
                _NL_DO,
                "\nwhere ",
                "\n:: ",
                "\n= ",
                # Split along type declarations
                "\ndata ",
                "\nnewtype ",
                _NL_TYPE,
                "\n:: ",
                # Split along module declarations
                "\nmodule ",
                # Split along import statements
                "\nimport ",
                "\nqualified ",
                "\nimport qualified ",
                # Split along typeclass declarations
                _NCLASS_KEY,
                "\ninstance ",
                # Split along case expressions
                _NL_CASE,
                # Split along guards in function definitions
                "\n| ",
                # Split along record field declarations
                "\ndata ",
                "\n= {",
                "\n, ",
                # Split by the normal type of lines
                _NL_NL,
                "\n",
                " ",
                "",
            ]
        else:
            raise ValueError(
                f"Language {language} is not supported! "
                f"Please choose from {list(Language)}"
            )

    @classmethod
    def from_language(
        cls, language: Language, **kwargs: Any
    ) -> RecursiveCodeTextSplitter:
        """
        Args:
            language (): Language
            chunk_size – 要返回的块的最大大小
            chunk_overlap – 块之间的字符重叠
            length_function – 测量给定块长度的函数
            keep_separator – 是否将分隔符保留在块中
            add_start_index – 如果 为 True，则在元数据中包含块的起始索引
            strip_whitespace – 如果 为 True，则从每个文档的开头和结尾去除空格
        Returns:
        """
        separators = cls.get_separators_for_language(language)
        return cls(separators=separators, is_separator_regex=True, **kwargs)

    def split_text(self, text: str) -> list[str]:
        return self._split_text(text, self._separators)

    def _split_text(self, text: str, separators: list[str]) -> list[str]:
        """Split incoming text and return chunks."""
        final_chunks = []
        # Get appropriate separator to use
        separator = separators[-1]
        new_separators = []
        for i, _s in enumerate(separators):
            _separator = _s if self._is_separator_regex else re.escape(_s)
            if _s == "":
                separator = _s
                break
            if re.search(_separator, text):
                separator = _s
                new_separators = separators[i + 1 :]
                break

        _separator = separator if self._is_separator_regex else re.escape(separator)
        splits = _split_text_with_regex(text, _separator, self._keep_separator)

        # Now go merging things, recursively splitting longer texts.
        _good_splits = []
        _separator = "" if self._keep_separator else separator
        for s in splits:
            if self._length_function(s) < self._chunk_size:
                _good_splits.append(s)
            else:
                if _good_splits:
                    merged_text = self._merge_splits(_good_splits, _separator)
                    final_chunks.extend(merged_text)
                    _good_splits = []
                if not new_separators:
                    final_chunks.append(s)
                else:
                    other_info = self._split_text(s, new_separators)
                    final_chunks.extend(other_info)
        if _good_splits:
            merged_text = self._merge_splits(_good_splits, _separator)
            final_chunks.extend(merged_text)
        return final_chunks
