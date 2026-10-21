# Compare complete disassembled method graphs; preserve all member/string comments.
# Constant-pool indices and their ldc encoding width have no semantic identity.
def normalized_javap_blocks(text)
 text.split(/\n\n/).map do |block|
  instructions=block.lines.filter_map rescue nil
  rows=block.lines.map{|line|m=line.match(/^\s*(\d+):\s+([a-z][a-z0-9_]*)\b(.*)$/);m && [m[1].to_i,m[2],m[3]]}.compact
  offsets=rows.map(&:first);labels=offsets.each_with_index.to_h
  label=->(n){labels.fetch(n.to_i){raise "Unresolved branch boundary #{n}"}}
  in_switch=false
  block.lines.map do |line|
   if (m=line.match(/^\s*(\d+):\s+([a-z][a-z0-9_]*)\b(.*)$/))
    opcode=m[2];operand=m[3].strip;opcode='ldc' if opcode=='ldc_w'
    if opcode.match?(/\A(if[a-z_]*|goto|goto_w|jsr|jsr_w)\z/)
     operand=operand.sub(/\A\d+/){|v|'L'+label.call(v).to_s}
    end
    in_switch=%w[tableswitch lookupswitch].include?(opcode)
    operand=operand.gsub(/#\d+/,'#POOL').gsub(/ +(?=\/\/)/,' ')
    "L#{label.call(m[1])}: #{opcode} #{operand}".rstrip
   elsif in_switch && (m=line.match(/^\s*(-?\d+|default):\s*(\d+)\s*$/))
    "#{m[1]}: L#{label.call(m[2])}"
   elsif in_switch && line.strip=='}'
    in_switch=false;'}'
   else
    line.rstrip
   end
  end.join("\n")
 end.sort
end
