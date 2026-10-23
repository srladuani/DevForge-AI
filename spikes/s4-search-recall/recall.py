"""S4-lite: lexical retrieval recall on past fix commits. usage: recall.py <repo> <src-prefix> <ext,..> [max]"""
import subprocess, re, sys, collections, json, math
repo, prefix, exts = sys.argv[1], sys.argv[2], tuple(sys.argv[3].split(','))
maxn = int(sys.argv[4]) if len(sys.argv) > 4 else 60
def git(*a):
    return subprocess.run(['git','-C',repo,*a],capture_output=True,text=True).stdout
STOP=set("the a an and or of to in on for with from by is are be this that it as at not no add added fix fixed fixes update updated remove removed change changed issue ticket bug support when after before into new use used using page screen user users data".split())
log = git('log','--no-merges','--format=%H\x1f%s','-n','3000').split('\n')
head_files = set(git('ls-files').split('\n'))
cases=[]
for line in log:
    if '\x1f' not in line: continue
    h,s = line.split('\x1f',1)
    if not re.search(r'\b(fix|bug|issue|error|fail|incorrect|wrong|missing|not working|crash|null)\b|[A-Z]{2,}-\d+', s, re.I): continue
    files=[f for f in git('show','--name-only','--format=',h).split('\n') if f.startswith(prefix) and f.endswith(exts) and f in head_files]
    words=[w for w in re.findall(r'[A-Za-z][A-Za-z0-9]{3,}', re.sub(r'[A-Z]{2,}-\d+','',s)) if w.lower() not in STOP]
    if files and len(words)>=2 and len(files)<=6: cases.append((h,s,files,list(dict.fromkeys(words))))
    if len(cases)>=maxn: break
# document frequency over HEAD (for IDF weighting) via git grep -l per word
def files_with(w):
    out=subprocess.run(['git','-C',repo,'grep','-l','-i','-w','--',w,'--',prefix],capture_output=True,text=True).stdout
    return [f for f in out.split('\n') if f.endswith(exts)]
total=sum(1 for f in head_files if f.startswith(prefix) and f.endswith(exts))
res=[]
for h,s,files,words in cases:
    score=collections.Counter()
    for w in words:
        fs=files_with(w)
        if not fs or len(fs)>total*0.3: continue
        idf=math.log(total/len(fs))
        for f in fs: score[f]+=idf
        # filename match bonus
    for f in list(score):
        base=f.rsplit('/',1)[-1].lower()
        for w in words:
            if w.lower() in base: score[f]+=3
    ranked=[f for f,_ in score.most_common(50)]
    rank=min([ranked.index(f)+1 for f in files if f in ranked] or [None], key=lambda x: x or 10**9)
    res.append((h[:9],s[:70],rank))
n=len(res)
def at(k): return sum(1 for r in res if r[2] and r[2]<=k)
print(json.dumps({'repo':repo.split('/')[-1],'source_files_indexed':total,'cases':n,
 'hit@1':at(1),'hit@5':at(5),'hit@10':at(10),'hit@20':at(20),'hit@50':at(50),'miss':sum(1 for r in res if not r[2])}))
for r in res[:12]: print(r)
