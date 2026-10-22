from pathlib import Path
import sqlite3, sys, unittest, json
R=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(R/'reference'))
from behavior_reference import assess_upload, ewma_next, delta_counter

def db(name):
    c=sqlite3.connect(':memory:')
    c.executescript((R/'database'/f'{name}_v1.sql').read_text())
    return c

class SchemaTests(unittest.TestCase):
    def test_schema_integrity(self):
        for name in ['control','telemetry','intel']:
            c=db(name)
            self.assertEqual(c.execute('PRAGMA integrity_check').fetchone()[0],'ok')
            self.assertEqual(c.execute('PRAGMA foreign_key_check').fetchall(),[])
            self.assertEqual(c.execute('PRAGMA user_version').fetchone()[0],1)
    def test_active_uid_reuse_requires_close(self):
        c=db('control')
        c.execute("INSERT INTO principal VALUES('p1',0,10001,10,NULL,'EXACT')")
        with self.assertRaises(sqlite3.IntegrityError):
            c.execute("INSERT INTO principal VALUES('p2',0,10001,20,NULL,'EXACT')")
        c.execute("UPDATE principal SET valid_until_ms=20 WHERE principal_id='p1'")
        c.execute("INSERT INTO principal VALUES('p2',0,10001,21,NULL,'EXACT')")
    def test_rule_fk_and_port_checks(self):
        c=db('control')
        c.execute("INSERT INTO profile VALUES('prof','Test','BALANCED','ALLOW','NOTIFY','PRESERVE_LAST_GOOD',1)")
        c.execute("INSERT INTO policy_revision VALUES('r','prof',NULL,1,'{}','hash','1')")
        row=('rule','r',None,'DENY','USER','WIFI','ANY',None,'TCP',80,90,0,1,None,None)
        c.execute('INSERT INTO policy_rule VALUES('+','.join('?'*15)+')',row)
        bad=list(row);bad[0]='bad';bad[9]=100;bad[10]=90
        with self.assertRaises(sqlite3.IntegrityError):
            c.execute('INSERT INTO policy_rule VALUES('+','.join('?'*15)+')',bad)
        bad=list(row);bad[0]='bad2';bad[2]='nonexistent-principal'
        with self.assertRaises(sqlite3.IntegrityError):
            c.execute('INSERT INTO policy_rule VALUES('+','.join('?'*15)+')',bad)
    def test_no_fake_probability_without_calibration(self):
        c=db('telemetry')
        c.execute("INSERT INTO feature_window VALUES('w','p','ctx',1,2,1,'{}','[]',1,1,'PENDING')")
        with self.assertRaises(sqlite3.IntegrityError):
            c.execute("INSERT INTO anomaly_assessment VALUES('a','w',NULL,'1',50,50,1,0.9,NULL,'[]',3)")
    def test_window_duplicate_and_missing_quality(self):
        c=db('telemetry')
        row=('w','p','ctx',1,2,1,'{}','[]',1,1,'PENDING')
        c.execute('INSERT INTO feature_window VALUES('+','.join('?'*11)+')',row)
        duplicate=list(row);duplicate[0]='w2'
        with self.assertRaises(sqlite3.IntegrityError):
            c.execute('INSERT INTO feature_window VALUES('+','.join('?'*11)+')',duplicate)
    def test_failed_feed_not_active_and_single_generation(self):
        c=db('intel')
        c.execute("INSERT INTO feed_source VALUES('s','Fixture','https://example.invalid','fixture','MALWARE',0,'PENDING','https://example.invalid/terms',NULL,NULL)")
        with self.assertRaises(sqlite3.IntegrityError):
            c.execute("UPDATE feed_source SET enabled=1 WHERE source_id='s'")
        c.execute("UPDATE feed_source SET license_state='APPROVED',enabled=1 WHERE source_id='s'")
        row=('g','s',1,None,None,'hash','VERIFIED',0,None,'ACTIVE')
        c.execute('INSERT INTO feed_generation VALUES('+','.join('?'*10)+')',row)
        for sig in ['FAILED','VERIFIED']:
            bad=list(row);bad[0]='g2';bad[6]=sig
            with self.assertRaises(sqlite3.IntegrityError):
                c.execute('INSERT INTO feed_generation VALUES('+','.join('?'*10)+')',bad)
    def test_indicator_provenance_keeps_sources(self):
        c=db('intel')
        for s in ['s1','s2']:
            c.execute('INSERT INTO feed_source VALUES(?,?,?,?,?,?,?,?,?,?)',(s,s,'https://example.invalid','fixture','MALWARE',1,'APPROVED','https://example.invalid/terms',1,None))
            c.execute('INSERT INTO feed_generation VALUES(?,?,?,?,?,?,?,?,?,?)',('g'+s,s,1,None,None,'hash','UNSIGNED',1,100,'ACTIVE'))
        c.execute("INSERT INTO indicator VALUES('i','DOMAIN','example.invalid',NULL,NULL,NULL)")
        for s in ['s1','s2']:
            c.execute('INSERT INTO indicator_provenance VALUES(?,?,?,?,?,?,?)',('i','g'+s,'MALWARE',1,1,100,None))
        self.assertEqual(c.execute('SELECT count(*) FROM active_indicator').fetchone()[0],2)
        c.execute("UPDATE feed_generation SET state='PREVIOUS' WHERE source_id='s1'")
        self.assertEqual(c.execute('SELECT count(*) FROM active_indicator').fetchone()[0],1)
    def test_example_json(self):
        for p in (R/'examples').glob('*.json'):json.loads(p.read_text())

class BehaviorTests(unittest.TestCase):
    def test_constant_history_stable_and_burst(self):
        self.assertEqual(assess_upload(1000,[1000]*40).anomaly_score,0)
        r=assess_upload(1000000,[1000]*40)
        self.assertGreaterEqual(r.anomaly_score,60)
        self.assertFalse(r.training_eligible)
        self.assertIsNone(r.malware_probability)
    def test_score_does_not_train(self):
        history=[1000]*40
        assess_upload(1000000,history)
        self.assertEqual(history,[1000]*40)
    def test_missing_is_not_zero(self):
        r=assess_upload(None,[1000]*40)
        self.assertIsNone(r.anomaly_score)
        self.assertFalse(r.training_eligible)
        self.assertEqual(assess_upload(0,[0]*40).anomaly_score,0)
    def test_cold_start_and_incomplete(self):
        self.assertEqual(assess_upload(1,[1]).reason,'INSUFFICIENT_HISTORY')
        self.assertEqual(assess_upload(1,[1]*40,completeness=0.3).reason,'INCOMPLETE_WINDOW')
    def test_known_ioc_survives_cold_start(self):
        r=assess_upload(1,[],known_ioc=True)
        self.assertEqual(r.risk_score,90)
        self.assertFalse(r.training_eligible)
    def test_invalid_input(self):
        for value in [-1,float('nan'),float('inf')]:
            with self.assertRaises(ValueError):assess_upload(value,[1]*40)
        with self.assertRaises(ValueError):assess_upload(1,[1]*40,scale_floor=0)
    def test_ewma(self):
        self.assertEqual(ewma_next(100,200,0.2),120)
        self.assertEqual(ewma_next(None,7),7)
    def test_counter_reset(self):
        self.assertIsNone(delta_counter(100,5,'b','b'))
        self.assertIsNone(delta_counter(100,150,'a','b'))
        self.assertEqual(delta_counter(100,150,'b','b'),50)

if __name__=='__main__':unittest.main(verbosity=2)
