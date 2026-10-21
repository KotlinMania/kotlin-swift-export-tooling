#!/usr/bin/env python3
"""Run the pinned real ProcMacro2 Native and Swift consumer, without product source edits."""
from pathlib import Path
import argparse, os, sys, json, hashlib, subprocess, re, datetime, shutil
TOOLING=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--consumer',type=Path,required=True)
p.add_argument('--output',type=Path,required=True)
p.add_argument('--distribution',type=Path,required=True)
p.add_argument('--cache',type=Path,required=True)
p.add_argument('--sdk',type=Path,required=True)
p.add_argument('--sdk-audit',type=Path,required=True)
p.add_argument('--sdk-download-audit',type=Path,required=True)
p.add_argument('--helper',type=Path,required=True)
p.add_argument('--gradle-cache-seed',type=Path,help='Optional closed binary cache; copied into the new owned output')
p.add_argument('--konan-cache-seed',type=Path,help='Optional closed Native dependency cache; copied into the new owned output')
opt=p.parse_args()
repo=opt.consumer.resolve();b=opt.output.resolve();sdk=opt.sdk.resolve()
assert not b.exists(),'New consumer output directory required'
b.mkdir(parents=True)
for name in ['logs','tmp','process-home','products','idea-system','idea-config','swift-cache/clang','swift-cache/module','harness/Tests/SwiftTestHarnessTests']:(b/name).mkdir(parents=True,exist_ok=True)
for seed,name in [(opt.gradle_cache_seed,'gradle-home'),(opt.konan_cache_seed,'konan-data')]:
 if seed:subprocess.run(['cp','-cRL',str(seed.resolve()),str(b/name)],check=True)
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def save(name,value):(b/name).write_text(json.dumps(value,indent=2)+'\n')
lock=json.loads((TOOLING/'inputs.lock.json').read_text())
component=opt.distribution.resolve()/('swift-export-embeddable-'+lock['version']+'.jar')
dist=json.loads((opt.distribution/'distribution.json').read_text())
assert sha(component)==dist['main_component_sha256']
cp=[]
for row in lock['tools']:
 if row['scope']!='worker':continue
 path=component if row['artifact']=='swift-export-embeddable' else opt.cache.resolve()/row['name']
 expected=sha(component) if path==component else row['sha256']
 assert sha(path)==expected
 cp.append({'path':str(path),'sha256':expected})
save('compiler-classpath.json',cp)
sources=json.loads((TOOLING/'verification/consumer-source-pins.json').read_text())
save('source-pins.json',{str(repo/name):h for name,h in sources.items()})
from importlib.util import spec_from_file_location,module_from_spec
spec=spec_from_file_location('sdkhelper',TOOLING/'scripts/prepare_native_sdk.py');helper=module_from_spec(spec);spec.loader.exec_module(helper)
sdkpins={str(sdk/name):h for name,h in helper.inventory(sdk).items()}
audit=json.loads(opt.sdk_audit.read_text())
expected_sdk=dict(audit['source_files']);expected_sdk[lock['native_header']['relative_path']]=lock['native_header']['candidate_sha256']
assert {str(sdk/name):h for name,h in expected_sdk.items()}==sdkpins,'SDK differs from complete helper creation receipt'
assert audit['changed_files']==[lock['native_header']['relative_path']] and audit['unchanged_native_binaries'] and not audit['installed_source_modified']
assert all(audit['source_files'][name]==h for name,h in lock['native_identity'].items())
assert audit['source_files'][lock['native_header']['relative_path']]==lock['native_header']['baseline_sha256']
assert sdkpins[str(sdk/lock['native_header']['relative_path'])]==lock['native_header']['candidate_sha256']
save('sdk-pins.json',sdkpins)
producer={str(p):sha(p) for p in [component,TOOLING/'inputs.lock.json',Path(__file__),TOOLING/'verification/consumer.init.gradle',opt.sdk_audit.resolve(),opt.sdk_download_audit.resolve(),opt.helper.resolve()]}
save('producer-inputs.json',producer);save('stock-inputs.json',{})
shutil.copyfile(TOOLING/'verification/consumer.init.gradle',b/'isolation.init.gradle')
shutil.copyfile(repo/'swift-test-harness/Tests/SwiftTestHarnessTests/ProcMacro2ExportTests.swift',b/'harness/Tests/SwiftTestHarnessTests/ProcMacro2ExportTests.swift')
package='// swift-tools-version: 6.0\nimport PackageDescription\n\nlet package = Package(\n    name: "SwiftTestHarness",\n    platforms: [\n        .macOS("15.0"),\n    ],\n    dependencies: [\n        .package(name: "ProcMacro2", path: "@OUTPUT@/build/SPMPackage/macosArm64/Debug")\n    ],\n    targets: [\n        .testTarget(\n            name: "SwiftTestHarnessTests",\n            dependencies: [\n                .product(name: "ProcMacro2Library", package: "ProcMacro2")\n            ],\n            linkerSettings: [\n                .unsafeFlags([\n                    "-L", "@OUTPUT@/products",\n                    "-lProcMacro2",\n                ]),\n            ]\n        ),\n    ],\n    swiftLanguageModes: [.v6]\n)\n'
(b/'harness/Package.swift').write_text(package.replace('@OUTPUT@',str(b)))
(b/'strict.xcconfig').write_text('SWIFT_VERSION = 5\nSWIFT_TREAT_WARNINGS_AS_ERRORS = YES\nGCC_TREAT_WARNINGS_AS_ERRORS = YES\n')
def verify():
 for name in ['source-pins.json','producer-inputs.json','sdk-pins.json']:
  assert all(sha(p)==h for p,h in json.loads((b/name).read_text()).items()),name+' input changed'
 for row in cp:assert sha(row['path'])==row['sha256']
 actual_sdk={str(sdk/name):h for name,h in helper.inventory(sdk).items()}
 if actual_sdk!=sdkpins:
  save('sdk-observed-after.json',actual_sdk)
  save('sdk-inventory-delta.json',{'added':{p:actual_sdk[p] for p in sorted(set(actual_sdk)-set(sdkpins))},'removed':{p:sdkpins[p] for p in sorted(set(sdkpins)-set(actual_sdk))},'changed':{p:{'before':sdkpins[p],'after':actual_sdk[p]} for p in sdkpins if p in actual_sdk and sdkpins[p]!=actual_sdk[p]}})
 assert actual_sdk==sdkpins,'SDK inventory set or hashes changed'
 if (b/'recipe-pins.json').exists():
  assert all(sha(path)==h for path,h in json.loads((b/'recipe-pins.json').read_text()).items()),'Frozen recipe changed'
 assert sha(b/'harness/Tests/SwiftTestHarnessTests/ProcMacro2ExportTests.swift')==sha(repo/'swift-test-harness/Tests/SwiftTestHarnessTests/ProcMacro2ExportTests.swift')
 assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()=='3d8718c2412be6aa99175ab88663edec8dbfa6db'
 assert not subprocess.check_output(['git','status','--porcelain'],cwd=repo,text=True)
def declarations(text):
 result=[];lines=text.splitlines();i=0
 while i<len(lines):
  if lines[i].lstrip().startswith('public '):
   block=lines[i].strip();i+=1
   while '{' not in block and i<len(lines):block+=' '+lines[i].strip();i+=1
   result.append(re.sub(r'\s+',' ',block.split('{',1)[0]).strip())
  else:i+=1
 return result
verify()
expected_declarations=json.loads((TOOLING/'verification/consumer-public-declarations.json').read_text())
save('baseline-public-declarations.json',expected_declarations)
env=os.environ.copy();env.update({'PROCMACRO2_ENUM_DIAGNOSTIC_ROOT':str(b),'GRADLE_USER_HOME':str(b/'gradle-home'),'KONAN_DATA_DIR':str(b/'konan-data'),'TMPDIR':str(b/'tmp')+'/','JAVA_TOOL_OPTIONS':'-Djava.io.tmpdir='+str(b/'tmp')+' -Didea.system.path='+str(b/'idea-system')+' -Didea.config.path='+str(b/'idea-config'),'CLANG_MODULE_CACHE_PATH':str(b/'swift-cache/clang'),'SWIFT_MODULECACHE_PATH':str(b/'swift-cache/module'),'SWIFTPM_MODULECACHE_OVERRIDE':str(b/'swift-cache/module'),'SDK_NAME':'macosx','CONFIGURATION':'Debug','ARCHS':'arm64','MACOSX_DEPLOYMENT_TARGET':'15.0','DEPLOYMENT_TARGET_SETTING_NAME':'MACOSX_DEPLOYMENT_TARGET','BUILT_PRODUCTS_DIR':str(b/'products'),'TARGET_BUILD_DIR':str(b/'products'),'FRAMEWORKS_FOLDER_PATH':'Frameworks','XCODE_XCCONFIG_FILE':str(b/'strict.xcconfig')})
gradle=shutil.which('gradle');assert gradle,'Gradle 9.8.2 required on PATH'
gradle_version=subprocess.check_output([gradle,'--version'],env=env,text=True,stderr=subprocess.STDOUT)
(b/'logs/gradle-version.log').write_text(gradle_version)
assert re.search(r'^Gradle 9\.8\.2$',gradle_version,re.M),'Wrong Gradle version'
args=[gradle,'embedSwiftExportForXcode','--no-daemon','--max-workers=2','--no-configuration-cache','--no-build-cache','--console=plain','--project-cache-dir',str(b/'project-cache'),'-I',str(b/'isolation.init.gradle'),'-Pkotlin.native.home='+str(sdk),'-Pkotlin.project.persistent.dir='+str(b/'kotlin-project'),'-Pkotlin.compiler.execution.strategy=in-process','-Dorg.gradle.jvmargs=-Xmx6g -XX:MaxMetaspaceSize=1g -Djava.io.tmpdir='+str(b/'tmp')]
swift=['/usr/bin/xcrun','swift','test','--jobs','2','--verbose','--package-path',str(b/'harness'),'--scratch-path',str(b/'swift-build'),'--cache-path',str(b/'swiftpm/cache'),'--config-path',str(b/'swiftpm/config'),'--security-path',str(b/'swiftpm/security'),'-Xswiftc','-warnings-as-errors','-Xswiftc','-v','-Xswiftc','-target','-Xswiftc','arm64-apple-macosx15.0']
request={'dependency_swift_mode':'5','authored_consumer_swift_mode':'6','native_and_all_swift_werror':True,'source_head':'3d8718c2412be6aa99175ab88663edec8dbfa6db','gradle':args,'swift_if_embed_passes':swift,'expected_tests':['tokenStreamParsingAndFormatting','tokenStreamParseFailure','literalAndPunctExport'],'component_sha256':sha(component),'stop_on_first_product_or_integrity_failure':True,'scope':'One reproducible release-candidate and helper-created SDK prepublication Native/Swift diagnostic. Dependencies Swift5, authored consumer Swift6, Werror both and Native. No production adoption/publication or Array/reverse claim.'}
save('request.json',request)
pins={str(p):sha(p) for p in [b/'isolation.init.gradle',b/'harness/Package.swift',b/'compiler-classpath.json',b/'source-pins.json',b/'producer-inputs.json',b/'stock-inputs.json',b/'request.json',b/'strict.xcconfig',b/'sdk-pins.json',Path(__file__)]}
save('recipe-pins.json',pins)
print('FROZEN REQUEST SHA256 '+sha(b/'request.json'),flush=True)
result={'source_head':request['source_head'],'swift_test_cases_executed':0,'accepted':False,'started_at':datetime.datetime.now(datetime.timezone.utc).isoformat()}
def run(phase,cmd,cwd):
 save('logs/'+phase+'-invocation.json',{'command':cmd,'cwd':str(cwd),'artifact_environment':{k:v for k,v in env.items() if k in ['GRADLE_USER_HOME','KONAN_DATA_DIR','TMPDIR','JAVA_TOOL_OPTIONS','PROCMACRO2_ENUM_DIAGNOSTIC_ROOT','XCODE_XCCONFIG_FILE']}})
 with (b/'logs'/str(phase+'.log')).open('w') as log:
  p=subprocess.Popen(cmd,cwd=str(cwd),env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
  save('active-process.json',{'phase':phase,'pid':p.pid,'command':cmd});print('STARTED '+phase+' PID '+str(p.pid),flush=True);code=p.wait()
 print('TERMINAL '+phase+' exit '+str(code),flush=True)
 return code
try:
 verify()
 result['gradle_exit']=run('gradle',args,repo)
 verify()
 gen=b/'build/SwiftExport/macosArm64/Debug/files/ProcMacro2/ProcMacro2.swift'
 if gen.exists():
  actual=declarations(gen.read_text());expected=expected_declarations;result['generated_public_declarations_unchanged']=actual==expected;result['generated_public_declarations_count']=len(actual)
  save('actual-public-declarations.json',actual)
  if actual!=expected:raise RuntimeError('Unexpected generated public API change')
 result['native_archives']=[{'path':str(p),'sha256':sha(p),'architecture':subprocess.check_output(['/usr/bin/xcrun','lipo','-info',str(p)],text=True,stderr=subprocess.STDOUT).strip()} for p in (b/'build').rglob('libProcMacro2.a')]
 assert sha(gen.with_suffix('.h'))=='2af18efb0dcbb574b26e378bfe3260c5559688b9b82ad775c1c06e4586d673f1', 'C header changed'
 if result['gradle_exit']:raise RuntimeError('Actual Native/Swift embed failed; preserve diagnostics and stop')
 assert (b/'products/libProcMacro2.a').is_file(),'Missing actual Native library archive'
 emitted={str(p):sha(p) for base in [b/'build/SwiftExport',b/'build/SPMPackage',b/'products'] for p in base.rglob('*') if p.is_file() and (p.suffix in ['.swift','.kt','.h','.a'] or p.name=='Package.swift')}
 headers=[p for p in emitted if p.endswith('/KotlinBase.h')]
 assert len(headers)>=2 and all(emitted[p]=='99186d1dd029a8ba5b66dae390a3aec0d6d0e5293eff641d85daab5e212723c3' for p in headers),'Incoherent generated Native runtime header'
 save('generated-inputs.json',emitted)
 result['swift_exit']=run('swift',swift,b/'harness')
 assert all(sha(p)==h for p,h in emitted.items()),'Emitted source/header/archive changed during tests'
 verify()
 text=(b/'logs/swift.log').read_text()
 product=[];manifests=[]
 for line in text.splitlines():
  if 'swift-frontend' not in line or '-module-name ' not in line:continue
  module=re.findall(r'-module-name\s+(\S+)',line)[-1]
  if module=='main' and ('Package.swift ' in line or 'Package.swift\"' in line):manifests.append(line);continue
  product.append({'module':module,'swift_version':re.findall(r'-swift-version\s+(\S+)',line)[-1] if '-swift-version ' in line else None,'werror':'-warnings-as-errors' in line,'mac15':bool(re.search(r'-target\s+arm64-apple-macos(?:x)?15\.0',line)),'command':line})
 result['product_frontends']=product;result['configuration_frontends_count']=len(manifests)
 assert product and all(p['werror'] and p['mac15'] for p in product),'Missing Werror/mac15 product frontend'
 for p in product:
  expected='6' if p['module']=='SwiftTestHarnessTests' else '5'
  if p['module']=='SwiftTestHarnessPackageTests':
   assert p['swift_version'] in [None,'6','6.0'],'Unexpected generated runner mode'
  else: assert p['swift_version'] in [expected,expected+'.0'],'Unexpected module language mode: '+p['module']
 result['generated_runner_mode_explicit']=all(p['swift_version'] is not None for p in product if p['module']=='SwiftTestHarnessPackageTests')
 modules={p['module'] for p in product};assert {'ProcMacro2','KotlinRuntimeSupport','KotlinCoroutineSupport','SwiftTestHarnessTests'}<=modules,'Missing actual dependency/test frontend proof'
 if result['swift_exit']:raise RuntimeError('Actual Swift tests failed')
 subprocess.check_call(['python3',str(repo/'verification/check_swift_tests.py'),str(b/'logs/swift.log')],env=env)
 result['swift_test_cases_executed']=3;result['accepted']=True
except Exception as error:
 result['failure']=str(error);print('STOP '+str(error),flush=True)
finally:
 try:verify();result['source_and_tool_inputs_unchanged']=True
 except Exception as error:result['source_and_tool_inputs_unchanged']=False;result['input_failure']=str(error)
 result['finished_at']=datetime.datetime.now(datetime.timezone.utc).isoformat();save('result.json',result);save('active-process.json',{'active':False,'all_owned_commands_terminal':True});print(json.dumps({k:v for k,v in result.items() if k!='product_frontends'},indent=2),flush=True)
raise SystemExit(0 if result['accepted'] else 1)
