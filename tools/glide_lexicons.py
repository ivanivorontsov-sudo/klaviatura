#!/usr/bin/env python3
"""Klaviatura 1.4.0: builds / extends the swipe lexicons in app/src/main/assets/glide.

  python3 tools/glide_lexicons.py extend            # hyphenated RU/EN words, EN contractions + programming words
  python3 tools/glide_lexicons.py lang pl FILE [N]  # new language from a hermitdave/FrequencyWords list (CC-BY-SA-4.0)
"""
import os, re, sys, unicodedata

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app', 'src', 'main', 'assets', 'glide')

def load(lang):
    d, order = {}, []
    with open(os.path.join(ROOT, lang + '.tsv'), encoding='utf-8') as f:
        for line in f:
            w, _, c = line.rstrip('\n').partition('\t')
            if w and w not in d:
                d[w] = int(c or 1); order.append(w)
    return d, order

def save(lang, d):
    items = sorted(d.items(), key=lambda kv: -kv[1])
    with open(os.path.join(ROOT, lang + '.tsv'), 'w', encoding='utf-8') as f:
        for w, c in items:
            f.write(f'{w}\t{c}\n')

RU_BASES = {
    'кто': 'кто кого кому кем ком', 'что': 'что чего чему чем чём',
    'какой': 'какой какая какое какие какого какую каким какому какими каких какой',
    'чей': 'чей чья чьё чьи чьего чьей чьему чьим чьих чьими чью',
    'где': 'где', 'куда': 'куда', 'откуда': 'откуда', 'когда': 'когда', 'как': 'как', 'почему': 'почему', 'зачем': 'зачем',
    'отчего': 'отчего', 'сколько': 'сколько скольких скольким', 'который': 'который которая которое которые которого которую которым которому которыми которых',
}
RU_FIXED = """из-за из-под по-моему по-твоему по-своему по-нашему по-вашему по-другому по-прежнему по-настоящему по-хорошему по-новому
по-старому по-русски по-английски по-немецки по-французски по-испански по-китайски по-японски по-итальянски по-украински по-польски
по-человечески по-дружески по-детски по-взрослому по-разному по-видимому по-любому по-всякому по-честному по-быстрому по-тихому
по-простому по-нормальному по-хорошему по-братски по-мужски по-женски по-моему всё-таки так-то вот-вот чуть-чуть еле-еле давным-давно
точь-в-точь крест-накрест волей-неволей мало-помалу худо-бедно туда-сюда тук-тук ха-ха ха-ха-ха хи-хи ой-ой ай-ай ну-ка на-ка
давай-ка смотри-ка слушай-ка погоди-ка подожди-ка ну-ну да-да нет-нет как-никак во-первых во-вторых в-третьих в-четвёртых в-пятых
плюс-минус тет-а-тет интернет-магазин интернет-магазина интернет-магазине интернет-магазины онлайн-курс онлайн-курсы бизнес-план
бизнес-плана вице-президент премьер-министр веб-сайт веб-сайта веб-сайте веб-страница веб-страницы ноу-хау хот-дог фан-клуб
шоу-бизнес диван-кровать северо-запад северо-востоке юго-запад юго-восток кое-как кое-где кое-куда кое-когда
""".split()

def extend():
    ru, _ = load('ru')
    add = {}
    for base, forms in RU_BASES.items():
        for f in forms.split():
            fb = ru.get(f, ru.get(base, 1000))
            add[f + '-то'] = max(40, int(fb * 0.08)); add[f + '-нибудь'] = max(20, int(fb * 0.02))
            add[f + '-либо'] = max(10, int(fb * 0.005))
            if base in ('кто', 'что', 'какой'): add['кое-' + f] = max(10, int(fb * 0.004))
    top = max(ru.values())
    for i, w in enumerate(RU_FIXED):
        add[w] = max(add.get(w, 0), 3000)
    for w in ('из-за', 'по-моему', 'всё-таки', 'кое-что', 'кое-кто', 'по-другому', 'по-прежнему', 'по-настоящему', 'чуть-чуть', 'вот-вот', 'во-первых', 'во-вторых'):
        add[w] = max(add[w], 60000)
    n = 0
    for w, c in add.items():
        if ru.get(w, 0) < c: ru[w] = c; n += 1
    save('ru', ru); print('ru: +', n, 'hyphenated, size', len(ru))

    en, _ = load('en')
    nums = 'twenty thirty forty fifty sixty seventy eighty ninety'.split()
    ones = 'one two three four five six seven eight nine'.split()
    hy = """well-known e-mail self-made long-term short-term follow-up built-in up-to-date real-time state-of-the-art open-source
so-called full-time part-time high-quality user-friendly check-in check-out sign-in sign-up log-in opt-in opt-out plug-in add-on
set-up x-ray t-shirt mother-in-law father-in-law brother-in-law sister-in-law one-on-one face-to-face step-by-step day-to-day
end-to-end peer-to-peer back-end front-end read-only run-time compile-time non-null pre-commit multi-threaded cross-platform third-party
re-enter co-founder co-worker ex-wife ex-girlfriend ex-boyfriend good-bye bye-bye uh-huh mm-hmm uh-oh well-being long-lasting
""".split() + [a + '-' + b for a in nums for b in ones]
    contr = """don't can't won't it's i'm you're that's isn't didn't doesn't i'll we're they're i've let's what's there's he's she's
wasn't couldn't wouldn't shouldn't haven't aren't i'd you'll we'll you've we've they've they'll he'll she'll weren't hasn't hadn't
who's where's how's here's ain't y'all you'd he'd she'd we'd they'd it'll that'll""".split()
    prog = """const let var func fn def elif lambda struct enum impl trait println printf sprintf malloc calloc nullptr bool int uint
boolean float double char void async await yield typeof instanceof namespace typedef sizeof volatile mutex semaphore coroutine
coroutines suspend override readonly stdin stdout stderr argv argc kwargs args params config env sudo chmod chown grep awk sed bash
zsh ssh scp curl wget tmux vim neovim emacs vscode intellij webpack vite react vue angular svelte nextjs nodejs npm npx yarn pnpm pip
venv conda deno graphql grpc protobuf websocket websockets http https tcp udp dns cdn css html dom ajax xml json yaml toml sql
nosql orm crud mvc mvvm devops kubernetes kubectl helm terraform ansible aws gcp azure serverless microservice microservices
monorepo submodule gitignore readme changelog dependabot iterable iterator iterators enumerate isinstance getattr setattr
dataclass pydantic numpy pandas matplotlib pytorch tensorflow keras sklearn jupyter cuda gpu cpu llm gpt tokenizer embeddings
embedding finetune finetuning inference github gitlab bitbucket localhost middleware endpoint endpoints refactor refactoring
deploy deployed deployment webhook webhooks oauth jwt api apis sdk cli gui repo repos regex regexp linter eslint prettier pytest
junit unittest boilerplate hashmap arraylist tuple tuples dict str len kotlin golang rust rustc cargo javascript typescript python
java swift scala haskell elixir erlang clojure php ruby rails django flask fastapi spring gradle maven jvm jdk dockerfile makefile
cmake docker nginx apache postgres postgresql mysql sqlite redis mongodb kafka rabbitmq elasticsearch linux ubuntu debian macos
ios android frontend backend fullstack codebase commit commits rebase merge cherry-pick pull push branch checkout stash diff
upstream downstream async/await todo todos fixme stacktrace traceback segfault nullable optional callback callbacks closure
closures generics generic polymorphism inheritance encapsulation singleton factory decorator decorators middleware runtime
compiler interpreter bytecode transpile transpiler minify bundler polyfill viewport flexbox grid utf ascii unicode base64 sha256
md5 uuid guid cron crontab systemd daemon stdout""".split()
    n = 0
    for w in hy + contr:
        c = 25000 if w in contr[:30] else 2500
        base = en.get(w.replace("'", ''), 0)
        c = max(c, int(base * 0.5)) if w in contr else c
        if en.get(w, 0) < c: en[w] = c; n += 1
    for w in prog:
        if '/' in w: continue
        if en.get(w, 0) < 3000: en[w] = 3000; n += 1
    save('en', en); print('en: +', n, 'size', len(en))

LETTERS_EXTRA = set("-'")

def clean(w):
    w = unicodedata.normalize('NFC', w.strip().lower())
    if not w or len(w) > 30: return None
    if not w[0].isalpha() or not w[-1].isalpha(): return None
    if any(not (ch.isalpha() or ch in LETTERS_EXTRA) for ch in w): return None
    if '--' in w or "''" in w: return None
    return w

def lang(code, path, limit=150000, mincount=3):
    d = {}
    with open(path, encoding='utf-8') as f:
        for line in f:
            parts = line.split()
            if len(parts) != 2: continue
            w = clean(parts[0]);
            try: c = int(parts[1])
            except ValueError: continue
            if w is None or c < mincount: continue
            if len(w) == 1 and c < 1000: continue
            d[w] = d.get(w, 0) + c
            if len(d) >= limit: break
    save(code, d); print(code, len(d))

if __name__ == '__main__':
    if sys.argv[1] == 'extend': extend()
    elif sys.argv[1] == 'lang': lang(sys.argv[2], sys.argv[3], int(sys.argv[4]) if len(sys.argv) > 4 else 150000)

BLOCK_ROOTS = {
    'pl': ['kurw', 'pierdol', 'pierdal', 'jeban', 'jebać', 'jebac', 'jebie', 'jebi', 'jebn', 'zajeb', 'wyjeb', 'przyjeb', 'odjeb',
           'chuj', 'pizd', 'kutas', 'skurw', 'cwel', 'dziwk'],
    'uk': ['хуй', 'хуя', 'хує', 'хуї', 'хую', 'хуям', 'пизд', 'пізд', 'бляд', 'блят', 'їба', 'їбу', 'їбе', 'їбн', 'єба', 'йоба',
           'мудак', 'мудил', 'підар', 'підор', 'уйоб', 'заїб'],
    'de': ['fick', 'fotze', 'wichs', 'hurensohn', 'scheiß', 'scheiss', 'arschloch', 'schlampe', 'nutte', 'missgeburt'],
}

PREFIXES = {
    'pl': ['za', 'wy', 'przy', 'od', 'roz', 'po', 'na', 'do', 'prze', 'ob', 'nad', 'pod', 'w', 'u', 's', 'z'],
    'uk': ['за', 'ви', 'при', 'від', 'роз', 'по', 'на', 'до', 'пере', 'об', 'під', 'над', 'в', 'у', 'з', 'с', 'о', 'на'],
}

def blocked():
    for code, roots in BLOCK_ROOTS.items():
        d, _ = load(code)
        if code == 'de':
            bad = sorted(w for w in d if any(r in w for r in roots))
        else:
            pre = PREFIXES[code]
            rx = re.compile('^(' + '|'.join(pre) + ')?(' + '|'.join(map(re.escape, roots)) + ')')
            bad = sorted(w for w in d if rx.match(w))
        with open(os.path.join(ROOT, f'blocked_{code}.txt'), 'w', encoding='utf-8') as f:
            f.write('\n'.join(bad) + '\n')
        print(code, len(bad), bad[:15])

if __name__ == '__main__' and sys.argv[1] == 'blocked': blocked()
