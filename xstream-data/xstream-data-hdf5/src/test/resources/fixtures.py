import sys,h5py,numpy as np
root=sys.argv[1]
with h5py.File(root+'/science.h5','w') as f:
 f.attrs['title']='Example';g=f.create_group('science');g.attrs['unit']='K'
 d=g.create_dataset('temperature',data=np.arange(24,dtype='>i2').reshape(4,6),chunks=(2,3),compression='gzip');d.attrs['scale_factor']=.1;d.dims[0].label='y';d.dims[1].label='x'
 f.create_dataset('scalar',data=np.uint64(18446744073709551615));f.create_dataset('empty',shape=(0,3),dtype='f4')
 f.create_dataset('text',data=['a','b'],dtype=h5py.string_dtype());f.create_dataset('compound',data=np.array([(1,2.)],dtype=[('i','i4'),('f','f8')]))
 f.create_dataset('complex',data=np.array([1+2j]));f.create_dataset('flag',data=[True,False])
 f['external']=h5py.ExternalLink('/etc/passwd','/');f['soft']=h5py.SoftLink('/science/temperature');g['cycle']=f
 layout=h5py.VirtualLayout(shape=(1,),dtype='i4');layout[:]=h5py.VirtualSource(root+'/missing.h5','/data',shape=(1,));f.create_virtual_dataset('virtual',layout)
 f.create_dataset('external_data',shape=(1,),dtype='i4',external=[('/etc/passwd',0,4)])
open(root+'/broken.h5','wb').write(b'\x89HDF\r\n\x1a\nbroken')
