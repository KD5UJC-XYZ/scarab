/* Test-only public arithmetic bridge. Sodium internals retain sodium-LICENSE. */
#define HAVE_TI_MODE 1
#include "private/ed25519_ref10.h"
#include "crypto_core_ed25519.h"
int probe_decode(unsigned char *out,const unsigned char *in) {
 ge25519_p3 p;if(!ge25519_is_canonical(in)||ge25519_frombytes(&p,in)!=0)return -1;
 ge25519_p3_tobytes(out,&p);return ge25519_has_small_order(in);
}
void probe_uniform(unsigned char *out,const unsigned char *in){ge25519_from_uniform(out,in);}
int probe_equation(unsigned char *out,const unsigned char *p,const unsigned char *q,const unsigned char *c,const unsigned char *s) {
 ge25519_p3 P,Q;ge25519_p2 R;unsigned char cn[32];
 if(!ge25519_is_canonical(p)||!ge25519_is_canonical(q)||ge25519_frombytes(&P,p)||ge25519_frombytes(&Q,q))return -1;
 crypto_core_ed25519_scalar_negate(cn,c);
 ge25519_double_scalarmult_vartime_variable(&R,cn,&P,s,&Q);ge25519_tobytes(out,&R);return 0;
}
#include "crypto_hash_sha512.h"
#include "crypto_vrf_ietfdraft03.h"
#include <string.h>
/* Exposes the exact native verify intermediate pipeline for public fixture tests. */
int probe_trace(unsigned char *out,const unsigned char *pk,const unsigned char *pi,const unsigned char *msg,unsigned long long len) {
 unsigned char ys[32],r[64],c[32]={0},s[32],u[32],v[32],domain[2]={4,1};ge25519_p3 y,g;
 if(ge25519_has_small_order(pk)||!ge25519_is_canonical(pk)||ge25519_frombytes(&y,pk)||!ge25519_is_canonical(pi)||ge25519_frombytes(&g,pi))return -1;
 memcpy(c,pi+32,16);memcpy(s,pi+48,32);if((s[31]&240)&&!sc25519_is_canonical(s))return -1;
 ge25519_p3_tobytes(ys,&y);crypto_hash_sha512_state hs;crypto_hash_sha512_init(&hs);crypto_hash_sha512_update(&hs,domain,2);crypto_hash_sha512_update(&hs,ys,32);crypto_hash_sha512_update(&hs,msg,len);crypto_hash_sha512_final(&hs,r);r[31]&=127;ge25519_from_uniform(out,r);
 /* Use actual fixed-base U and variable-base V functions. */
 unsigned char cn[32];ge25519_p2 U,V;ge25519_p3 H;ge25519_frombytes(&H,out);crypto_core_ed25519_scalar_negate(cn,c);
 ge25519_double_scalarmult_vartime(&U,cn,&y,s);ge25519_double_scalarmult_vartime_variable(&V,cn,&g,s,&H);ge25519_tobytes(out+32,&U);ge25519_tobytes(out+64,&V);
 domain[1]=2;crypto_hash_sha512_init(&hs);crypto_hash_sha512_update(&hs,domain,2);crypto_hash_sha512_update(&hs,out,32);crypto_hash_sha512_update(&hs,pi,32);crypto_hash_sha512_update(&hs,out+32,64);crypto_hash_sha512_final(&hs,r);memcpy(out+96,r,16);
 return crypto_vrf_ietfdraft03_proof_to_hash(out+112,pi);
}
