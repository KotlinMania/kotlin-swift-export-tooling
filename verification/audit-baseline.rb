require 'json'
require 'open3'
require_relative 'class-file-members'
require_relative 'normalize-javap'
archive, classes, selected = ARGV
rows = JSON.parse(selected).map do |entry|
  original, error, status = Open3.capture3('unzip', '-p', archive, entry)
  raise error unless status.success?
  rebuilt = File.binread(File.join(classes, entry))
  raise "Baseline member/access-flag mismatch: #{entry}" unless ClassFileMembers.parse(original) == ClassFileMembers.parse(rebuilt)
  name = entry.delete_suffix('.class').tr('/', '.')
  before, err, ok = Open3.capture3('javap', '-p', '-c', '-classpath', archive, name)
  raise err unless ok.success?
  after, err, ok = Open3.capture3('javap', '-p', '-c', '-classpath', classes, name)
  raise err unless ok.success?
  raise "Baseline instruction mismatch: #{entry}" unless normalized_javap_blocks(before) == normalized_javap_blocks(after)
  {entry: entry, member_flags_equal: true, instructions_equal: true}
end
puts JSON.pretty_generate({selected_classes: rows, passed: !rows.empty?})
