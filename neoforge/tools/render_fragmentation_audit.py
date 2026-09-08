"""Render measured integer-height contours and count <=4-block connected shelves.
Run using a Python environment with numpy and Pillow. Inputs are float32 big-endian
rasters from TerrainFragmentationAudit, 256 x 256 blocks, centred on the origin.
"""
from pathlib import Path
import csv
import sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont
root=Path(sys.argv[1])
suffix=sys.argv[2] if len(sys.argv)>2 else ""
templates=['plains','hills_1','dales','mountains_1','plateau']
seeds=[9,7331,8844]
def metrics(h):
    levels=np.floor(h).astype(int);seen=np.zeros(levels.shape,dtype=bool)
    tiny_area=tiny_count=0
    bumps=pits=0
    for x in range(256):
        for z in range(256):
            if seen[x,z]:continue
            y=levels[x,z];queue=[(x,z)];seen[x,z]=True;area=0;border=False;lower=higher=False
            while queue:
                a,b=queue.pop();area+=1;border|=a==0 or a==255 or b==0 or b==255
                for c,d in [(a-1,b),(a+1,b),(a,b-1),(a,b+1)]:
                    if 0<=c<256 and 0<=d<256:
                        lower|=levels[c,d]<y;higher|=levels[c,d]>y
                    if 0<=c<256 and 0<=d<256 and not seen[c,d] and levels[c,d]==y:
                        seen[c,d]=True;queue.append((c,d))
            if area<=4 and not border:tiny_count+=1;tiny_area+=area
            if area<=16 and not border:
                if lower and not higher:bumps+=1
                if higher and not lower:pits+=1
    dx=np.abs(np.diff(h,axis=0)).ravel();dz=np.abs(np.diff(h,axis=1)).ravel()
    return [tiny_count,tiny_area,bumps,pits,float(np.ptp(h)),float(np.quantile(np.r_[dx,dz],.95))]
with (root/'block-fragmentation.csv').open('w') as f:
    writer=csv.writer(f);writer.writerow(['version','seed','template','tiny_shelves','tiny_blocks','enclosed_bumps_16','enclosed_pits_16','relief','axis_slope_p95'])
    for version in ['before','after']:
        for seed in seeds:
            for t in templates:
                h=np.fromfile(root/(version+suffix)/f'{seed}-{t}-eroded.bin',dtype='>f4').reshape(256,256)
                row=[version,seed,t,*metrics(h)];writer.writerow(row);print(row,flush=True)
canvas=Image.new('RGB',(1100,1730),'#172432');draw=ImageDraw.Draw(canvas)
font=ImageFont.truetype('/usr/share/fonts/google-noto-vf/NotoSans[wght].ttf',22) if Path('/usr/share/fonts/google-noto-vf/NotoSans[wght].ttf').exists() else ImageFont.load_default(size=22)
draw.text((24,14),'Measured block-height contours | Seed 7331',font=font,fill='white')
draw.text((24,45),'256 x 256 blocks per panel | block centres | before water.',font=font,fill='#cad6df')
draw.text((24,76),'r21',font=font,fill='white');draw.text((568,76),'r22 / TerraForged methods',font=font,fill='white')
for row,t in enumerate(templates[:3]):
    draw.text((24,112+row*534),t,font=font,fill='white')
    for col,version in enumerate(['before','after']):
        h=np.fromfile(root/(version+suffix)/f'7331-{t}-eroded.bin',dtype='>f4').reshape(256,256).T
        y=np.floor(h);edge=np.zeros(h.shape,dtype=bool)
        edge[1:,:]|=y[1:,:]!=y[:-1,:];edge[:,1:]|=y[:,1:]!=y[:,:-1]
        rgb=np.full((256,256,3),[118,156,91],dtype=np.uint8);rgb[edge]=[48,81,45]
        canvas.paste(Image.fromarray(rgb).resize((512,512),Image.Resampling.NEAREST),(24+col*544,142+row*534))
canvas.save(root/'terrain-fragments-r21-r22.png')
