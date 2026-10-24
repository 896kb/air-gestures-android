#!/usr/bin/env python3
"""Build the handwriting dictionary (app/src/main/assets/words.txt): English words and how
often they're used, from FrequencyWords (github.com/hermitdave/FrequencyWords; its lists are CC BY-SA 4.0, so this one is too; counts
from OpenSubtitles 2018, so it leans conversational).

    curl -LO https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/en/en_50k.txt
    scripts/words.py en_50k.txt > app/src/main/assets/words.txt

Kept: words of letters a–z only that a spelling dictionary knows (/usr/share/dict/american-english),
or among the most common (which keeps "ok", "gonna", names people write).
"""

import sys

COMMON = 3000


def main(path):
    known = {w.strip() for w in open("/usr/share/dict/american-english") if w.strip().isalpha()}
    print("# word count: FrequencyWords en_50k (CC BY-SA 4.0, OpenSubtitles 2018), filtered by scripts/words.py; this list is CC BY-SA 4.0 too")
    rank = 0
    for line in open(path, encoding="utf-8"):
        word, count = line.split()
        rank += 1
        if word.isascii() and word.isalpha() and word.islower() and (word in known or rank <= COMMON):
            print(word, count)


if __name__ == "__main__":
    main(sys.argv[1])
