import sys
if sys.flags.optimize:
 raise RuntimeError("Run without Python optimization; required provenance and ABI assertions must remain enabled")
import pathlib,json,hashlib,subprocess,tarfile,platform
P=pathlib.Path(__file__).resolve().parent
S=P/'libsodium-dbb48cce5429cb6585c9034f002568964f1ce567'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
with tarfile.open(P/'source.tar.gz') as t:
 for m in t.getmembers():
  if m.isfile():
   assert hashlib.sha256(t.extractfile(m).read()).hexdigest()==sha(P/m.name),m.name
source_hashes={str(p.relative_to(S)):sha(p) for p in S.rglob('*') if p.is_file()}
(P/'source-sha256.json').write_text(json.dumps(source_hashes,indent=2,sort_keys=True)+'\n')
command=next(l for l in (P/'verify-compile-command.txt').read_text().splitlines() if '--mode=compile gcc ' in l)
command='gcc '+command.split('--mode=compile gcc ',1)[1]
command=command.replace('-c -o crypto_sign/ed25519/ref10/libsodium_la-open.lo','-E -dM')
macro=subprocess.check_output(command,shell=True,cwd=P/'build/src/libsodium',text=True)
(P/'verify-preprocessor-macros.txt').write_text(macro)
assert not any(l.startswith('#define ED25519_COMPAT') for l in macro.splitlines())
preprocessed=subprocess.check_output(command.replace('-E -dM','-E -P'),shell=True,cwd=P/'build/src/libsodium',text=True)
(P/'verify-preprocessed.c').write_text(preprocessed)
assert 'if (sc25519_is_canonical(sig + 32) == 0 ||' in preprocessed
assert 'if (sig[63] & 224)' not in preprocessed
makefile=(P/'build/src/libsodium/Makefile').read_text()
settings={k:next(l.split(' = ',1)[1] for l in makefile.splitlines() if l.startswith(k+' = ')) for k in ('CC','CFLAGS','CPPFLAGS','DEFS','LDFLAGS')}
manifest=dict(source_url='https://codeload.github.com/IntersectMBO/libsodium/tar.gz/dbb48cce5429cb6585c9034f002568964f1ce567',source_commit='dbb48cce5429cb6585c9034f002568964f1ce567',source_archive_sha256=sha(P/'source.tar.gz'),source_files_unchanged_from_archive=True,source_file_count=len(source_hashes),license='ISC; full source LICENSE retained',configure_argv=['../libsodium-dbb48cce5429cb6585c9034f002568964f1ce567/configure','--prefix='+str(P/'build/../install'),'--enable-shared','--disable-static','--disable-dependency-tracking'],configure_environment=dict(CC='gcc',CFLAGS='-O2 -g0 -UED25519_COMPAT',CPPFLAGS='unset',LDFLAGS='unset'),make_command='make -j2',make_check_run=False,make_install_run=False,compiler=subprocess.check_output(['gcc','--version'],text=True).splitlines()[0],compiler_target=subprocess.check_output(['gcc','-dumpmachine'],text=True).strip(),make_version=subprocess.check_output(['make','--version'],text=True).splitlines()[0],platform=platform.platform(),effective_make_settings=settings,ED25519_COMPAT_defined=False,non_COMPAT_preprocessed_branch_verified=True,library='build/src/libsodium/.libs/libsodium.so.23.3.0',library_sha256=sha(P/'build/src/libsodium/.libs/libsodium.so.23.3.0'),source_oracle_not_shipped_node_binary=True,official_node_compiler_and_flags_established=False,native_api_calls=['sodium_version_string','crypto_sign_ed25519_verify_detached'],no_sodium_init=True,no_signing_or_keygen=True,no_node_or_network_listener=True)
(P/'provenance.json').write_text(json.dumps(manifest,indent=2)+'\n')
paths=['source.tar.gz','source-sha256.json','provenance.json','verify_public.py','record_provenance.py','results.json','summary.json','configure.log','build.log','build/config.log','build/config.status','build/src/libsodium/Makefile','verify-compile-command.txt','verify-preprocessor-macros.txt','verify-preprocessed.c','build/src/libsodium/.libs/libsodium.so.23.3.0']
(P/'sha256.json').write_text(json.dumps({p:sha(P/p) for p in paths},indent=2)+'\n')
print('Source archive unchanged; non-COMPAT preprocessing verified; provenance and hashes saved.')
