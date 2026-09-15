import json, os, shutil, subprocess, tempfile

ROOT = r'E:\mc\傀儡复活\template-mod-template-26.2'
os.chdir(ROOT)

# Snapshot the whole generated+data tree so we can always restore, no matter
# what a probe does to it.
SNAPSHOT = os.path.join(tempfile.gettempdir(), 'gc_assets_snapshot')
if os.path.exists(SNAPSHOT):
    shutil.rmtree(SNAPSHOT)
shutil.copytree('src/main/resources', os.path.join(SNAPSHOT, 'resources'))


def restore_all():
    shutil.rmtree('src/main/resources')
    shutil.copytree(os.path.join(SNAPSHOT, 'resources'), 'src/main/resources')


def run():
    r = subprocess.run(['D:/python/python.exe', 'tools/validate_block_assets.py'],
                       capture_output=True, text=True)
    return r.returncode, [l for l in r.stdout.strip().splitlines() if l.strip()]


results = []

# T1: typo a vanilla texture name
p = 'src/main/resources/assets/golem_covenant/models/block/soul_altar.json'
orig = json.load(open(p, encoding='utf-8'))
bad = json.loads(json.dumps(orig))
bad['textures']['side'] = 'minecraft:block/crying_obsidiann'
json.dump(bad, open(p, 'w', encoding='utf-8'), indent=2)
results.append(('vanilla texture typo', run()))
restore_all()

# T2: delete a blockstate
sp = 'src/main/resources/assets/golem_covenant/blockstates/covenant_stele.json'
os.remove(sp)
results.append(('missing blockstate', run()))
restore_all()

# T3: delete a loot table
lp = 'src/main/resources/data/golem_covenant/loot_table/blocks/soul_altar.json'
os.remove(lp)
results.append(('missing loot table', run()))
restore_all()

# T4: delete an item model
mp = 'src/main/resources/assets/golem_covenant/models/item/altar_core.json'
os.remove(mp)
results.append(('missing item model', run()))
restore_all()

# T5: recipe pointing at an unregistered id
rp = 'src/main/resources/data/golem_covenant/recipe/capacity_shard.json'
rorig = json.load(open(rp, encoding='utf-8'))
rbad = json.loads(json.dumps(rorig))
rbad['result']['id'] = 'golem_covenant:capacity_shrad'
json.dump(rbad, open(rp, 'w', encoding='utf-8'), indent=2)
results.append(('recipe unknown id', run()))
restore_all()

# T6: blockstate pointing at a model that does not exist
sp2 = 'src/main/resources/assets/golem_covenant/blockstates/soul_altar.json'
sdata = json.load(open(sp2, encoding='utf-8'))
sdata['variants']['']['model'] = 'golem_covenant:block/nope'
json.dump(sdata, open(sp2, 'w', encoding='utf-8'), indent=2)
results.append(('dangling model ref', run()))
restore_all()

for name, (code, lines) in results:
    print(f'{name:22} exit={code}  {lines[0] if lines else ""}')
    for extra in lines[1:3]:
        print(f'{"":22}   {extra.strip()}')

code, lines = run()
print(f'{"restored":22} exit={code}  {lines[0] if lines else ""}')
shutil.rmtree(SNAPSHOT, ignore_errors=True)
