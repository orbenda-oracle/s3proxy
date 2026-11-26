import os
import subprocess
import unittest
from pathlib import Path
import yaml

CHART=Path(__file__).resolve().parents[1]/'chart'

class ChartTest(unittest.TestCase):
    def render(self,*args,success=True):
        result=subprocess.run(['helm','template','gateway',str(CHART),'--set','image.repository=registry.example/s3proxy',*args],capture_output=True,text=True,env={**os.environ,'KUBECONFIG':'/dev/null'})
        if success:
            self.assertEqual(result.returncode,0,result.stderr)
            return [v for v in yaml.safe_load_all(result.stdout) if v]
        self.assertNotEqual(result.returncode,0)
        return result.stderr

    def test_default_deployment_keeps_secrets_external_and_non_root(self):
        docs=self.render();self.assertNotIn('Secret',[d['kind'] for d in docs])
        pod=next(d for d in docs if d['kind']=='Deployment')['spec']['template']['spec'];c=pod['containers'][0]
        self.assertFalse(pod['automountServiceAccountToken'])
        self.assertEqual(pod['nodeSelector']['kubernetes.io/arch'],'amd64')
        self.assertTrue(pod['securityContext']['runAsNonRoot'])
        self.assertEqual(pod['securityContext']['runAsUser'],10001)
        self.assertTrue(c['securityContext']['readOnlyRootFilesystem'])
        self.assertFalse(c['securityContext']['allowPrivilegeEscalation'])
        self.assertEqual(c['securityContext']['capabilities']['drop'],['ALL'])
        self.assertEqual(len([v for v in pod['volumes'] if 'secret' in v]),3)
        self.assertTrue(all('subPath' not in m and m['readOnly'] for m in c['volumeMounts'] if m['name']!='tmp'))
        self.assertIn('tcpSocket',c['readinessProbe'])
        svc=next(d for d in docs if d['kind']=='Service');self.assertEqual(svc['spec']['type'],'ClusterIP')
        self.assertEqual(svc['spec']['ports'][0]['targetPort'],'https')

    def test_oke_load_balancer_is_internal_tcp(self):
        docs=self.render('--set','service.type=LoadBalancer')
        svc=next(d for d in docs if d['kind']=='Service')
        self.assertEqual(svc['metadata']['annotations']['service.beta.kubernetes.io/oci-load-balancer-internal'],'true')
        self.assertEqual(svc['spec']['ports'][0]['protocol'],'TCP')

    def test_missing_repository_cannot_render_a_deployable_chart(self):
        r=subprocess.run(['helm','template','gateway',str(CHART)],capture_output=True,text=True,env={**os.environ,'KUBECONFIG':'/dev/null'})
        self.assertNotEqual(r.returncode,0)
        self.assertIn('repository',r.stderr)

    def test_digest_pin_and_custom_secret_names_are_rendered(self):
        digest='sha256:'+'a'*64
        docs=self.render('--set','image.digest='+digest,'--set','secrets.users=my-users')
        pod=next(d for d in docs if d['kind']=='Deployment')['spec']['template']['spec']
        self.assertEqual(pod['containers'][0]['image'],'registry.example/s3proxy@'+digest)
        self.assertIn('my-users',[v['secret']['secretName'] for v in pod['volumes'] if 'secret' in v])

    def test_empty_secret_and_public_override_are_rejected(self):
        self.render('--set','secrets.native=',success=False)
        self.render('--set','service.type=LoadBalancer','--set-string','service.annotations.service\\.beta\\.kubernetes\\.io/oci-load-balancer-internal=false',success=False)

if __name__=='__main__':unittest.main()
