"""Derive ocean routing from the installed Minecraft 1.21.1 data resources.
Run from neoforge/: python3 tools/generate_ocean_settings.py
Only continentalness and the project's sea-level datum are changed. Other native
noise functions, caves, aquifers, ores and surface rules remain upstream values.
"""
import json
from pathlib import Path
from zipfile import ZipFile

root = Path('src/main/resources/data/adventureworldgen/worldgen')
with ZipFile('build/moddev/artifacts/neoforge-21.1.249-client-extra-aka-minecraft-resources.jar') as resources:
    names = set(resources.namelist())
    changed = {}
    memo = {}

    def transform(value):
        if isinstance(value, str) and value.startswith('minecraft:'):
            key = value.split(':')[1]
            if key == 'overworld/continents':
                return -0.65
            name = 'data/minecraft/worldgen/density_function/' + key + '.json'
            if name not in names:
                return value
            if key not in memo:
                original = json.loads(resources.read(name))
                updated = transform(original)
                memo[key] = value
                if updated != original:
                    changed[key] = updated
                    memo[key] = 'adventureworldgen:ocean/' + key
            return memo[key]
        if isinstance(value, dict):
            return {key: transform(item) for key, item in value.items()}
        if isinstance(value, list):
            return [transform(item) for item in value]
        return value

    settings = json.loads(resources.read('data/minecraft/worldgen/noise_settings/overworld.json'))
    settings['noise_router'] = transform(settings['noise_router'])
    settings['sea_level'] = 64  # Native filling uses y < sea_level.
    outputs = {'noise_settings/ocean.json': settings}
    outputs.update({'density_function/ocean/' + key + '.json': value for key, value in changed.items()})
    for name, value in outputs.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value, indent=2) + '\n')
