#!/usr/bin/env python3
"""Build the next-word table (app/src/main/assets/pairs.txt): for each word, the words most
often written after it, from Tatoeba's English sentences (tatoeba.org, CC BY 2.0 FR; short
everyday sentences, so it suits messages).

    curl -LO https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2
    scripts/pairs.py eng_sentences.tsv.bz2 app/src/main/assets/words.txt > app/src/main/assets/pairs.txt

Lines are `word next count next count …`, best first; `<s>` is a sentence's start. Only words
the dictionary has are kept, and a word with an apostrophe ("don't") breaks the run, as the
dictionary has no such words. Tatoeba's stock names (Tom, Mary) aren't offered.
"""

import bz2
import re
import sys
from collections import Counter, defaultdict

START = "<s>"
# Next words kept per word, and the fewest times a pair must be seen.
KEEP = 6
FEWEST = 3
TOKEN = re.compile(r"[A-Za-z']+|[.?!]")
# Tatoeba's stock names, in a great many of its sentences: not offered as next words.
NAMES = {"tom", "mary"}


def main(sentences, dictionary):
    known = {line.split()[0] for line in open(dictionary) if not line.startswith("#") and line.strip()}
    pairs = defaultdict(Counter)
    with bz2.open(sentences, "rt", encoding="utf-8") as lines:
        for line in lines:
            text = line.rstrip("\n").split("\t")[-1]
            previous = START
            for token in TOKEN.findall(text):
                if token in ".?!":
                    previous = START
                    continue
                word = token.lower()
                if word not in known:
                    previous = None
                    continue
                if previous is not None:
                    pairs[previous][word] += 1
                previous = word
    print("# word next count …: Tatoeba English sentences (CC BY 2.0 FR), by scripts/pairs.py")
    for word in sorted(pairs):
        best = [(nxt, n) for nxt, n in pairs[word].most_common(KEEP + len(NAMES)) if n >= FEWEST and nxt not in NAMES]
        best = best[:KEEP]
        if best:
            print(word, " ".join(f"{nxt} {n}" for nxt, n in best))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
