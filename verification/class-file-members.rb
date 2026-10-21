module ClassFileMembers
  class Cursor
    def initialize(bytes)
      @bytes = bytes
      @offset = 0
    end

    def take(count)
      check(count)
      result = @bytes.byteslice(@offset, count)
      @offset += count
      result
    end

    def skip(count)
      check(count)
      @offset += count
    end

    def u1
      take(1).unpack1('C')
    end

    def u2
      take(2).unpack1('n')
    end

    def u4
      take(4).unpack1('N')
    end

    private

    def check(count)
      raise 'Truncated class file' if count < 0 || @offset + count > @bytes.bytesize
    end
  end

  def self.read(path)
    parse(File.binread(path))
  end

  def self.parse(bytes)
    cursor = Cursor.new(bytes)
    raise 'Invalid class magic' unless cursor.u4 == 0xcafebabe
    cursor.skip(4)
    pool = Array.new(cursor.u2)
    index = 1
    while index < pool.size
      tag = cursor.u1
      case tag
      when 1
        pool[index] = cursor.take(cursor.u2).force_encoding('UTF-8')
      when 3, 4, 9, 10, 11, 12, 17, 18
        cursor.skip(4)
      when 5, 6
        cursor.skip(8)
        index += 1
      when 7, 8, 16, 19, 20
        cursor.skip(2)
      when 15
        cursor.skip(3)
      else
        raise "Unsupported constant pool tag #{tag}"
      end
      index += 1
    end
    cursor.skip(6)
    cursor.skip(cursor.u2 * 2)
    members = []
    %w[field method].each do |kind|
      cursor.u2.times do
        flags = cursor.u2
        name = pool[cursor.u2]
        descriptor = pool[cursor.u2]
        raise 'Invalid member name/descriptor' unless name.is_a?(String) && descriptor.is_a?(String)
        cursor.u2.times do
          cursor.u2
          cursor.skip(cursor.u4)
        end
        members << {'kind' => kind, 'name' => name, 'descriptor' => descriptor, 'flags' => flags, 'private' => (flags & 0x0002) != 0}
      end
    end
    members
  end
end
