from PIL import Image,ImageDraw,ImageFont
import struct,math
from pathlib import Path
p=Path('neoforge/build/reports/terrain-r6');n=800
samples=list(struct.iter_unpack('>ffBB',(p/'map.bin').read_bytes()))
height=Image.new('RGB',(n,n));biomes=Image.new('RGB',(n,n));palette=[(120,168,90),(48,102,77),(214,191,122),(218,230,231),(42,92,130)]
for i,(h,w,k,b) in enumerate(samples):
 x=i%n;y=i//n
 if k>0: color=(51,134,185) if k!=1 else (35,80,118)
 else:
  t=max(0,min(1,(h-64)/160));c=[(112,156,84),(161,151,98),(150,132,117),(242,238,226)];u=t*2.99;j=int(u);f=u-j
  raw=tuple(c[j][a]*(1-f)+c[min(3,j+1)][a]*f for a in range(3))
  dx=samples[i+1][0]-h if x<n-1 else 0; dz=samples[i+n][0]-h if y<n-1 else 0
  shade=max(.45,min(1.25,.9-(dx+dz)/20));color=tuple(int(min(255,max(0,v*shade))) for v in raw)
 height.putpixel((x,y),color);biomes.putpixel((x,y),palette[b])
out=Image.new('RGB',(1660,930),(244,246,249));out.paste(height,(20,70));out.paste(biomes,(840,70));d=ImageDraw.Draw(out)
f=ImageFont.truetype('/usr/share/fonts/google-noto-vf/NotoSans[wght].ttf',24) if Path('/usr/share/fonts/google-noto-vf/NotoSans[wght].ttf').exists() else ImageFont.load_default(size=24)
d.text((20,18),'Terrain and rivers | seed 7331 | radius limit 3000',fill=(25,35,48),font=f);d.text((840,18),'Biome boundaries | same coordinates',fill=(25,35,48),font=f)
d.text((20,887),'Height-field diagnostic, before erosion. Ocean shown schematically. Extent: -3200 to +3200 blocks.',fill=(45,60,75),font=ImageFont.load_default(size=20))
out.save(p/'terrain-preview.png')
